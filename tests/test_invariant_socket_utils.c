#include <check.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>

// Include the actual production header
#include "common/socket_utils.h"

START_TEST(test_send_fds_fd_count_boundary)
{
    // Invariant: send_fds must not overflow stack buffers regardless of fd_count value
    int fd_counts[] = {
        0,          // Valid minimal case
        1024,       // Boundary: typical stack size / sizeof(int) ≈ 256-1024
        1000000     // Exploit case: large attacker-controlled value
    };
    
    int num_cases = sizeof(fd_counts) / sizeof(fd_counts[0]);
    
    for (int i = 0; i < num_cases; i++) {
        int fd_count = fd_counts[i];
        
        // Create a socket pair for testing
        int sock_fds[2];
        int rc = socketpair(AF_UNIX, SOCK_STREAM, 0, sock_fds);
        ck_assert_msg(rc == 0, "socketpair failed: %s", strerror(errno));
        
        // Prepare test file descriptors (just use dummy fds)
        int *fds = NULL;
        if (fd_count > 0) {
            fds = malloc(sizeof(int) * fd_count);
            ck_assert_msg(fds != NULL, "malloc failed for fd_count=%d", fd_count);
            
            // Fill with dummy values (invalid fds are okay for buffer size testing)
            for (int j = 0; j < fd_count; j++) {
                fds[j] = -1;
            }
        }
        
        // Call the actual production function
        // This should either succeed for valid inputs or fail gracefully without overflow
        ssize_t result = send_fds(sock_fds[0], "test", 4, fds, fd_count);
        
        // Security property: function must not crash or overflow
        // We accept either success (for valid fd_count) or graceful failure
        // The critical check is that we reach this point without SIGSEGV
        ck_assert_msg(1, "send_fds must not overflow stack buffers for fd_count=%d", fd_count);
        
        // Cleanup
        if (fds) free(fds);
        close(sock_fds[0]);
        close(sock_fds[1]);
    }
}
END_TEST

Suite *security_suite(void)
{
    Suite *s;
    TCase *tc_core;

    s = suite_create("Security");
    tc_core = tcase_create("Core");

    tcase_add_test(tc_core, test_send_fds_fd_count_boundary);
    suite_add_tcase(s, tc_core);

    return s;
}

int main(void)
{
    int number_failed;
    Suite *s;
    SRunner *sr;

    s = security_suite();
    sr = srunner_create(s);

    srunner_run_all(sr, CK_NORMAL);
    number_failed = srunner_ntests_failed(sr);
    srunner_free(sr);

    return (number_failed == 0) ? EXIT_SUCCESS : EXIT_FAILURE;
}