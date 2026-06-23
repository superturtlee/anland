#include <errno.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "../common/protocol.h"
#include "../common/socket_utils.h"

#define MAX_EVENTS 16
#define MAX_FDS    4

struct client {
    int  ctrl_fd;
    bool is_consumer;
};

static struct client *consumer;
static struct client *producer;
static int epoll_fd;
static volatile bool running = true;

static struct screen_info stored_screen;
static bool has_screen_info;

static int deposited_fds[MAX_FDS];
static int deposited_fd_count;

static bool producer_waiting_screen;
static bool producer_waiting_fds;

static void handle_signal(int sig)
{
    (void)sig;
    running = false;
}

static void client_free(struct client *c)
{
    if (!c) return;
    if (c->ctrl_fd >= 0) {
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, c->ctrl_fd, NULL);
        close(c->ctrl_fd);
    }
    free(c);
}

static void clear_deposited_fds(void)
{
    for (int i = 0; i < deposited_fd_count; i++)
        close(deposited_fds[i]);
    deposited_fd_count = 0;
}

static int send_ctrl(int fd, uint32_t type)
{
    struct ctrl_msg msg = { .type = type, .size = 0 };
    return send_all(fd, &msg, sizeof(msg));
}

static int send_screen_info_msg(int fd)
{
    struct ctrl_msg hdr = { .type = CTRL_MSG_SCREEN_INFO, .size = sizeof(struct screen_info) };
    uint8_t buf[sizeof(struct ctrl_msg) + sizeof(struct screen_info)];
    memcpy(buf, &hdr, sizeof(hdr));
    memcpy(buf + sizeof(hdr), &stored_screen, sizeof(stored_screen));
    return send_all(fd, buf, sizeof(buf));
}

static void try_deliver_fds(void)
{
    if (!producer || deposited_fd_count < 4) {
        producer_waiting_fds = true;
        return;
    }

    struct ctrl_msg msg = { .type = CTRL_MSG_FDS_READY, .size = 0 };
    if (send_fds(producer->ctrl_fd, &msg, sizeof(msg),
                 deposited_fds, deposited_fd_count) < 0) {
        fprintf(stderr, "daemon: failed to send fds to producer\n");
        producer_waiting_fds = true;
        return;
    }

    if (consumer)
        send_ctrl(consumer->ctrl_fd, CTRL_MSG_FDS_READY);

    for (int i = 0; i < deposited_fd_count; i++)
        close(deposited_fds[i]);
    deposited_fd_count = 0;
    producer_waiting_fds = false;

    fprintf(stderr, "daemon: fds delivered to producer\n");
}

/*
 * Tear down whichever role this client holds, reset the associated state, then free
 * it. Used both for real disconnects (EPOLLHUP / recv error) and to evict a stale
 * client when a new one takes over the same role -- whether the old client is still
 * alive or already a ghost, finding one is reason enough to drop it.
 *
 * The role pointer is cleared BEFORE client_free() so that any event still queued for
 * this client in the current epoll batch fails the "is it a current role?" guard in
 * the main loop and is skipped instead of dereferencing freed memory.
 */
static void drop_client(struct client *c)
{
    if (!c)
        return;
    if (c == consumer) {
        fprintf(stderr, "daemon: consumer disconnected\n");
        consumer = NULL;
        /* The deposited fds belong to the consumer that just left; a future producer
         * must never be handed this stale set. Drop them together with the consumer. */
        clear_deposited_fds();
    } else if (c == producer) {
        fprintf(stderr, "daemon: producer disconnected\n");
        producer = NULL;
        producer_waiting_screen = false;
        producer_waiting_fds = false;
    }
    client_free(c);
}

static void handle_client_data(struct client *c)
{
    struct ctrl_msg hdr;
    int fds[MAX_FDS];
    int fd_count = 0;

    int n = recv_fds(c->ctrl_fd, &hdr, sizeof(hdr), fds, MAX_FDS, &fd_count);
    if (n <= 0) {
        drop_client(c);
        return;
    }

    uint8_t payload[sizeof(struct screen_info)];
    if (hdr.size > 0) {
        if (hdr.size > sizeof(payload) || recv_all(c->ctrl_fd, payload, hdr.size) < 0) {
            drop_client(c);
            return;
        }
    }

    switch (hdr.type) {
    case CTRL_MSG_CONSUMER_HELLO:
        if (c == consumer && fd_count >= 3) {
            clear_deposited_fds();
            memcpy(deposited_fds, fds, sizeof(int) * fd_count);
            deposited_fd_count = fd_count;
            fprintf(stderr, "daemon: consumer re-deposited %d fds\n", fd_count);
            if (producer_waiting_fds)
                try_deliver_fds();
        }
        break;

    case CTRL_MSG_SCREEN_INFO:
        if (c == consumer && hdr.size == sizeof(struct screen_info)) {
            struct screen_info si;
            memcpy(&si, payload, sizeof(si));
            /* Always accept the consumer's screen info, even if it differs from a
             * previous connection — the Android display may have rotated or switched
             * resolution. Overwrite and forward to any waiting producer. */
            stored_screen = si;
            has_screen_info = true;
            fprintf(stderr, "daemon: screen info %ux%u fmt=%u\n",
                    si.width, si.height, si.format);
            if (producer_waiting_screen && producer) {
                send_screen_info_msg(producer->ctrl_fd);
                producer_waiting_screen = false;
            }
        }
        break;

    case CTRL_MSG_PICKUP_FDS:
        if (c == producer)
            try_deliver_fds();
        break;

    default:
        break;
    }
}

static void handle_new_connection(int listen_fd)
{
    int client_fd = accept(listen_fd, NULL, NULL);
    if (client_fd < 0)
        return;

    struct ctrl_msg hdr;
    int fds[MAX_FDS];
    int fd_count = 0;

    int n = recv_fds(client_fd, &hdr, sizeof(hdr), fds, MAX_FDS, &fd_count);
    if (n < (int)sizeof(struct ctrl_msg)) {
        close(client_fd);
        return;
    }

    struct client *c = calloc(1, sizeof(*c));
    c->ctrl_fd = client_fd;

    if (hdr.type == CTRL_MSG_CONSUMER_HELLO) {
        /* Evict any prior consumer (alive or ghost) and its stale deposit before this
         * one takes over the role. */
        if (consumer)
            drop_client(consumer);
        c->is_consumer = true;
        consumer = c;

        clear_deposited_fds();
        memcpy(deposited_fds, fds, sizeof(int) * fd_count);
        deposited_fd_count = fd_count;
        fprintf(stderr, "daemon: consumer connected, %d fds\n", fd_count);

        if (producer_waiting_fds)
            try_deliver_fds();

    } else if (hdr.type == CTRL_MSG_PRODUCER_HELLO) {
        /* Evict any prior producer (alive or ghost) before this one takes over. */
        if (producer)
            drop_client(producer);
        c->is_consumer = false;
        producer = c;
        producer_waiting_screen = false;
        producer_waiting_fds = false;
        fprintf(stderr, "daemon: producer connected\n");

        if (has_screen_info)
            send_screen_info_msg(client_fd);
        else
            producer_waiting_screen = true;

    } else {
        close(client_fd);
        free(c);
        return;
    }

    struct epoll_event ev = { .events = EPOLLIN | EPOLLHUP | EPOLLERR, .data.ptr = c };
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, client_fd, &ev);
}

int main(int argc, char **argv)
{
    const char *sock_path = (argc > 1) ? argv[1] : "/data/local/tmp/display_daemon.sock";

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGPIPE, SIG_IGN);

    unlink(sock_path);
    int listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        perror("socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        return 1;
    }
    if (listen(listen_fd, 4) < 0) {
        perror("listen");
        return 1;
    }

    epoll_fd = epoll_create1(0);
    struct epoll_event ev = { .events = EPOLLIN, .data.ptr = NULL };
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, listen_fd, &ev);

    fprintf(stderr, "daemon: listening on %s\n", sock_path);

    struct epoll_event events[MAX_EVENTS];
    while (running) {
        int nfds = epoll_wait(epoll_fd, events, MAX_EVENTS, 1000);
        for (int i = 0; i < nfds; i++) {
            if (events[i].data.ptr == NULL) {
                handle_new_connection(listen_fd);
            } else {
                struct client *c = events[i].data.ptr;
                /* A client freed earlier in this same batch -- a real disconnect, or
                 * one evicted by a new connection taking over its role -- leaves a
                 * stale event behind. Skip anything that is no longer a current role
                 * so we never touch freed memory. */
                if (c != consumer && c != producer)
                    continue;
                if (events[i].events & (EPOLLHUP | EPOLLERR))
                    drop_client(c);
                else
                    handle_client_data(c);
            }
        }
    }

    clear_deposited_fds();
    client_free(consumer);
    client_free(producer);
    close(listen_fd);
    close(epoll_fd);
    unlink(sock_path);
    fprintf(stderr, "daemon: shutdown\n");
    return 0;
}
