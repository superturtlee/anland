#ifndef DISPLAY_PROTOCOL_H
#define DISPLAY_PROTOCOL_H

#include <stdint.h>

#define CTRL_MSG_CONSUMER_HELLO  1
#define CTRL_MSG_PRODUCER_HELLO  2
#define CTRL_MSG_SCREEN_INFO     7
#define CTRL_MSG_REJECT          8
#define CTRL_MSG_PICKUP_FDS      9
#define CTRL_MSG_FDS_READY      10

#define DATA_MSG_BUF_READY       100
#define DATA_MSG_REFRESH_DONE    101
#define DATA_MSG_INPUT_EVENT     102
#define DATA_MSG_OUTPUT_EVENT    103
#define DATA_MSG_BUFS_READY      200

#define MAX_BUFS 8

struct ctrl_msg {
    uint32_t type;
    uint32_t size;
    uint8_t  payload[];
} __attribute__((packed));

struct data_msg {
    uint32_t type;
    uint32_t size;
    uint8_t  payload[];
} __attribute__((packed));

struct screen_info {
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t refresh;
} __attribute__((packed));

struct buf_info {
    uint32_t stride;
    uint32_t width;      /* buffer logical width  (consumer-side native resolution) */
    uint32_t height;     /* buffer logical height (consumer-side native resolution) */
    uint32_t format;
    uint64_t modifier;
    uint32_t offset;
} __attribute__((packed));

#define INPUT_TYPE_TOUCH          1
#define INPUT_TYPE_KEY            2
#define INPUT_TYPE_POINTER_MOTION 3
#define INPUT_TYPE_POINTER_BUTTON 4
#define INPUT_TYPE_POINTER_AXIS   5
#define INPUT_TYPE_TOUCH_FRAME    6
/* Not really input: the consumer reports its current display refresh rate over
 * the same data channel so the producer can repace its RenderLoop at runtime.
 * Deliberately reuses the InputEvent framing (DATA_MSG_INPUT_EVENT) so the
 * producer's poll_input_event() drains it like any other event instead of
 * stalling the stream on an unknown DATA_MSG_* header. */
#define INPUT_TYPE_DISPLAY_REFRESH 7
#define INPUT_TYPE_CLIPBOARD      8

#define INPUT_ACTION_DOWN    0
#define INPUT_ACTION_UP      1
#define INPUT_ACTION_MOVE    2

struct InputEvent {
    uint32_t type;
    union {
        struct {
            int32_t  action;
            float    x;
            float    y;
            int32_t  pointer_id;
        } touch;
        struct {
            int32_t  action;
            int32_t  keycode;
        } key;
        struct {
            float    x;
            float    y;
            float    dx;
            float    dy;
        } pointer_motion;
        struct {
            uint32_t button;
            int32_t  pressed;
        } pointer_button;
        struct {
            uint32_t axis;
            float    value;
            int32_t  discrete;
        } pointer_axis;
        struct {
            uint32_t refresh_mhz; // current display refresh rate, milli-Hz
        } display;
        struct {
            uint32_t size; //这个packet只是通知包 作为header真正数据会集中发送,这里通知随后数据的大小
        } clipboard;
        struct {
            uint32_t padding[4];
        };
    };
} __attribute__((packed));

struct OutputEvent{
    uint32_t type;
    union {
        struct {
            uint32_t size; //这个packet只是通知包 作为header真正数据会集中发送,这里通知随后数据的大小
        } clipboard;
        struct
        {
            uint32_t padding[4];
        };

    };
} __attribute__((packed));

#define OUTPUT_TYPE_CLIPBOARD 1


#endif
