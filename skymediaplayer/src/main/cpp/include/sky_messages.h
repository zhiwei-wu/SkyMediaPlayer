//
// Sky Message Type Definitions - C/C++ compatible
//

#ifndef MY_PLAYER_SKY_MESSAGES_H
#define MY_PLAYER_SKY_MESSAGES_H

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================================
// Sky Message Type Definitions
// ============================================================================

// Basic control messages
#define SKY_MSG_FLUSH                       0

// Error and status messages
#define SKY_MSG_ERROR                       100     /* arg1 = error , ffplay error code*/
#define SKY_MSG_PREPARED                    200
#define SKY_MSG_COMPLETED                   300

// Video related messages
#define SKY_MSG_VIDEO_SIZE_CHANGED          400     /* arg1 = width, arg2 = height */
#define SKY_MSG_SAR_CHANGED                 401     /* arg1 = sar.num, arg2 = sar.den */
#define SKY_MSG_VIDEO_RENDERING_START       402
#define SKY_MSG_AUDIO_RENDERING_START       403
#define SKY_MSG_VIDEO_ROTATION_CHANGED      404     /* arg1 = degree */
#define SKY_MSG_AUDIO_DECODED_START         405
#define SKY_MSG_VIDEO_DECODED_START         406
#define SKY_MSG_OPEN_INPUT                  407
#define SKY_MSG_FIND_STREAM_INFO            408
#define SKY_MSG_COMPONENT_OPEN              409
#define SKY_MSG_COMPONENT_OPEN_ERR          410
#define SKY_MSG_VIDEO_SEEK_RENDERING_START  411
#define SKY_MSG_AUDIO_SEEK_RENDERING_START  412

// Buffering related messages
#define SKY_MSG_BUFFERING_START             500
#define SKY_MSG_BUFFERING_END               501
#define SKY_MSG_BUFFERING_UPDATE            502     /* arg1 = buffering head position in time, arg2 = minimum percent in time or bytes */
#define SKY_MSG_BUFFERING_BYTES_UPDATE      503     /* arg1 = cached data in bytes,            arg2 = high water mark */
#define SKY_MSG_BUFFERING_TIME_UPDATE       504     /* arg1 = cached duration in milliseconds, arg2 = high water mark */

// Seek and playback messages
#define SKY_MSG_SEEK_COMPLETE               600     /* arg1 = seek position,                   arg2 = error */
#define SKY_MSG_PLAYBACK_STATE_CHANGED      700
#define SKY_MSG_TIMED_TEXT                  800
#define SKY_MSG_ACCURATE_SEEK_COMPLETE      900     /* arg1 = current position*/
#define SKY_MSG_GET_IMG_STATE               1000    /* arg1 = timestamp, arg2 = result code, obj = file name*/

// Decoder messages
#define SKY_MSG_VIDEO_DECODER_OPEN          10001

// Request messages
#define SKY_MSG_REQ_START                       20001
#define SKY_MSG_REQ_PAUSE                       20002
#define SKY_MSG_REQ_SEEK                        20003

// ============================================================================

#ifdef __cplusplus
}
#endif

#endif //MY_PLAYER_SKY_MESSAGES_H