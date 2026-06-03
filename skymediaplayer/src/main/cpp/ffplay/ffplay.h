//
// 将 ffplay.c 中 结构类型，宏定义 抽取到这里
//

#ifndef MY_PLAYER_FFPLAY_H
#define MY_PLAYER_FFPLAY_H

#include "SDL3/SDL.h"
#include "libavutil/frame.h"
#include "libavcodec/avcodec.h"
#include "libavutil/fifo.h"
#include "libavformat/avformat.h"
#include "libavutil/tx.h"
#include "libavfilter/avfilter.h"

// Include sky message definitions
#include "sky_messages.h"

#ifdef __cplusplus
extern "C" {
#endif

// 被移除恢复的定义
#define SDL_MIX_MAXVOLUME 128  // SDL2.x 开始被移除

#define MAX_QUEUE_SIZE (15 * 1024 * 1024)
#define MIN_FRAMES 25
#define EXTERNAL_CLOCK_MIN_FRAMES 2
#define EXTERNAL_CLOCK_MAX_FRAMES 10

/* Minimum SDL audio buffer_ size, in samples. */
#define SDL_AUDIO_MIN_BUFFER_SIZE 512
/* Calculate actual buffer_ size keeping in mind not cause too frequent audio callbacks */
#define SDL_AUDIO_MAX_CALLBACKS_PER_SEC 30

/* Step size for volume control in dB */
#define SDL_VOLUME_STEP (0.75)

/* no AV sync correction is done if below the minimum AV sync threshold */
#define AV_SYNC_THRESHOLD_MIN 0.04
/* AV sync correction is done if above the maximum AV sync threshold */
#define AV_SYNC_THRESHOLD_MAX 0.1
/* If a frame duration is longer than this, it will not be duplicated to compensate AV sync */
#define AV_SYNC_FRAMEDUP_THRESHOLD 0.1
/* no AV correction is done if too big error */
#define AV_NOSYNC_THRESHOLD 10.0

/* maximum audio speed change to get correct sync */
#define SAMPLE_CORRECTION_PERCENT_MAX 10

/* external clock speed adjustment constants for realtime sources based on buffer_ fullness */
#define EXTERNAL_CLOCK_SPEED_MIN  0.900
#define EXTERNAL_CLOCK_SPEED_MAX  1.010
#define EXTERNAL_CLOCK_SPEED_STEP 0.001

/* we use about AUDIO_DIFF_AVG_NB A-V differences to make the average */
#define AUDIO_DIFF_AVG_NB   20

/* polls for possible required screen refresh at least this often, should be less than 1/fps */
#define REFRESH_RATE 0.01

/* NOTE: the size must be big enough to compensate the hardware audio buffersize size */
/* TODO: We assume that a decoded and resampled frame fits into this buffer_ */
#define SAMPLE_ARRAY_SIZE (8 * 65536)

#define CURSOR_HIDE_DELAY 1000000

#define USE_ONEPASS_SUBTITLE_RENDER 1

typedef struct MyAVPacketList {
    AVPacket *pkt;
    int serial;
} MyAVPacketList;

typedef struct PacketQueue {
    AVFifo *pkt_list;
    int nb_packets;
    int size;
    int64_t duration;
    int abort_request;
    int serial;
    SDL_Mutex *mutex;
    SDL_Condition *cond;
} PacketQueue;

#define VIDEO_PICTURE_QUEUE_SIZE 3
#define SUBPICTURE_QUEUE_SIZE 16
#define SAMPLE_QUEUE_SIZE 9
#define FRAME_QUEUE_SIZE FFMAX(SAMPLE_QUEUE_SIZE, FFMAX(VIDEO_PICTURE_QUEUE_SIZE, SUBPICTURE_QUEUE_SIZE))

typedef void (*Sky_AudioCallback) (void *userdata, Uint8 * stream, int len);

typedef struct SkyAudioSpec {
        SDL_AudioSpec sdl_audioSpec;
        uint8_t silence;
        uint16_t samples;
        uint32_t size;
        Sky_AudioCallback callback;
        void *userdata;
} SkyAudioSpec;

typedef struct AudioParams {
    int freq;
    AVChannelLayout ch_layout;
    enum AVSampleFormat fmt;
    int frame_size;
    int bytes_per_sec;
} AudioParams;

typedef struct Clock {
    double pts;           /* clock base */
    double pts_drift;     /* clock base minus time at which we updated the clock */
    double last_updated;
    double speed;
    int serial;           /* clock is based on a packet with this serial */
    int paused;
    int *queue_serial;    /* pointer to the current packet queue serial, used for obsolete clock detection */
} Clock;

typedef struct FrameData {
    int64_t pkt_pos;
} FrameData;

/* Common struct for handling all types of decoded data and allocated render buffers. */
typedef struct Frame {
    AVFrame *frame;
    AVSubtitle sub;
    int serial;
    double pts;           /* presentation timestamp for the frame */
    double duration;      /* estimated duration of the frame */
    int64_t pos;          /* byte position of the frame in the input file */
    int width;
    int height;
    int format;
    AVRational sar;
    int uploaded;
    int flip_v;
} Frame;

typedef struct FrameQueue {
    Frame queue[FRAME_QUEUE_SIZE];
    int rindex;
    int windex;
    int size;
    int max_size;
    int keep_last;
    int rindex_shown;
    SDL_Mutex *mutex;
    SDL_Condition *cond;
    PacketQueue *pktq;
} FrameQueue;

enum {
    AV_SYNC_AUDIO_MASTER, /* default choice */
    AV_SYNC_VIDEO_MASTER,
    AV_SYNC_EXTERNAL_CLOCK, /* synchronize to an external clock */
};

typedef struct Decoder {
    AVPacket *pkt;
    PacketQueue *queue;
    AVCodecContext *avctx;
    int pkt_serial;
    int finished;
    int packet_pending;
    SDL_Condition *empty_queue_cond;
    int64_t start_pts;
    AVRational start_pts_tb;
    int64_t next_pts;
    AVRational next_pts_tb;
    SDL_Thread *decoder_tid;
    bool first_frame_decoded;
} Decoder;

typedef struct VideoState {
    SDL_Thread *read_tid;
    const AVInputFormat *iformat;
    int abort_request;
    int force_refresh;
    int paused;
    int last_paused;
    int queue_attachments_req;
    int seek_req;
    int seek_flags;
    int64_t seek_pos;
    int64_t seek_rel;
    int read_pause_return;
    AVFormatContext *ic;
    int realtime;

    Clock audclk;
    Clock vidclk;
    Clock extclk;

    FrameQueue pictq;
    FrameQueue subpq;
    FrameQueue sampq;

    Decoder auddec;
    Decoder viddec;
    Decoder subdec;

    int audio_stream;

    int av_sync_type;

    double audio_clock;
    int audio_clock_serial;
    double audio_diff_cum; /* used for AV difference average computation */
    double audio_diff_avg_coef;
    double audio_diff_threshold;
    int audio_diff_avg_count;
    AVStream *audio_st;
    PacketQueue audioq;
    int audio_hw_buf_size;
    uint8_t *audio_buf;
    uint8_t *audio_buf1;
    unsigned int audio_buf_size; /* in bytes */
    unsigned int audio_buf1_size;
    int audio_buf_index; /* in bytes */
    int audio_write_buf_size;
    int audio_volume;
    int muted;
    struct AudioParams audio_src;
    struct AudioParams audio_filter_src;
    struct AudioParams audio_tgt;
    struct SwrContext *swr_ctx;
    int frame_drops_early;
    int frame_drops_late;

    enum ShowMode {
        SHOW_MODE_NONE = -1, SHOW_MODE_VIDEO = 0, SHOW_MODE_WAVES, SHOW_MODE_RDFT, SHOW_MODE_NB
    } show_mode;
    int16_t sample_array[SAMPLE_ARRAY_SIZE];
    int sample_array_index;
    int last_i_start;
    AVTXContext *rdft;
    av_tx_fn rdft_fn;
    int rdft_bits;
    float *real_data;
    AVComplexFloat *rdft_data;
    int xpos;
    double last_vis_time;
    SDL_Texture *vis_texture;
    SDL_Texture *sub_texture;
    SDL_Texture *vid_texture;

    int subtitle_stream;
    AVStream *subtitle_st;
    PacketQueue subtitleq;

    double frame_timer;
    double frame_last_returned_time;
    double frame_last_filter_delay;
    int video_stream;
    AVStream *video_st;
    PacketQueue videoq;
    double max_frame_duration;      // maximum duration of a frame - above this, we consider the jump a timestamp discontinuity
    struct SwsContext *sub_convert_ctx;
    int eof;

    char *filename;
    int width, height, xleft, ytop;
    int step;

    int vfilter_idx;
    AVFilterContext *in_video_filter;   // the first filter in the video chain
    AVFilterContext *out_video_filter;  // the last filter in the video chain
    AVFilterContext *in_audio_filter;   // the first filter in the audio chain
    AVFilterContext *out_audio_filter;  // the last filter in the audio chain
    AVFilterGraph *agraph;              // audio filter graph

    int last_video_stream, last_audio_stream, last_subtitle_stream;

    SDL_Condition *continue_read_thread;

    // 额外定义内容

    /**
     * 方便在 c 中调用 c++ 代码，将对象传回
     * 注意释放
     */
    void* skyPlayer;

    // 硬件解码标志
    int hw_decoder_active;          // 硬件解码器是否已激活（1=是，0=否）
    int hw_surface_mode;            // 是否处于 Surface 直渲模式（1=是，0=否）

    // 独立刷新线程管理
    SDL_Thread *refresh_tid;        // 刷新线程句柄
    int refresh_thread_abort;       // 刷新线程退出标志

    // 动态音频滤镜支持
    char *audio_filters;            // 动态音频滤镜描述字符串
    SDL_Mutex *audio_filter_mutex;  // 保护 audio_filters 的互斥锁
    int audio_filter_changed;       // 滤镜变更标志（1=需要重新配置）

    // 异步 Whisper 处理支持
    SDL_Thread *whisper_tid;        // Whisper 处理线程
    int whisper_abort;              // Whisper 线程退出标志
    AVFilterGraph *whisper_agraph;  // Whisper 专用滤镜图
    AVFilterContext *whisper_in_filter;   // Whisper 输入滤镜
    AVFilterContext *whisper_out_filter;  // Whisper 输出滤镜
    AVFifo *whisper_frame_queue;    // 待处理的音频帧队列
    SDL_Mutex *whisper_mutex;       // Whisper 队列互斥锁
    SDL_Condition *whisper_cond;    // Whisper 队列条件变量
    struct AudioParams whisper_filter_src; // Whisper 滤镜源参数
    int64_t whisper_current_pts;    // 当前正在处理的音频帧 PTS（用于 PTS 同步）
    
    // ==================== 独立 Whisper 解码流（超前解码方案）====================
    // 核心思想：使用独立的 AVFormatContext 和解码器，始终比播放位置超前 N 秒解码
    // 这样可以保证 Whisper 有足够的时间处理音频，生成字幕后能及时显示
    
    // 独立的格式上下文和解码器
    AVFormatContext *whisper_ic;           // 独立的格式上下文（打开同一个文件）
    AVCodecContext *whisper_avctx;         // 独立的音频解码器
    PacketQueue whisper_audioq;            // 独立的音频包队列
    int whisper_audio_stream;              // 音频流索引
    AVStream *whisper_audio_st;            // 音频流指针
    
    // 独立的线程
    SDL_Thread *whisper_read_tid;          // 独立的读取线程
    SDL_Thread *whisper_decode_tid;        // 独立的解码线程
    SDL_Condition *whisper_read_cond;      // 读取线程条件变量
    
    // 超前解码控制
    volatile int64_t whisper_decode_pts;   // 当前 Whisper 解码位置（微秒，AV_TIME_BASE）
    double whisper_lead_time;              // 目标超前时间（秒），默认 10 秒
    double whisper_min_lead_time;          // 最小超前时间（秒），低于此值加速解码
    double whisper_max_lead_time;          // 最大超前时间（秒），超过此值暂停解码
    
    // Seek 同步
    volatile int whisper_seek_req;         // Whisper seek 请求标志
    volatile int64_t whisper_seek_pos;     // Whisper seek 目标位置（微秒）
    int whisper_seek_flags;                // Whisper seek 标志
    
    // 状态标志
    volatile int whisper_decode_abort;     // 解码线程退出标志
    volatile int whisper_read_abort;       // 读取线程退出标志
    volatile int whisper_eof;              // Whisper 读取到文件末尾
    volatile int whisper_enabled;          // Whisper 功能是否启用

} VideoState;

/**
 * 带时间戳的 Whisper 字幕结构
 * 用于 PTS 同步方案，确保字幕与音频/视频同步显示
 */
typedef struct WhisperSubtitle {
    char *text;           // 字幕文本
    int64_t start_pts;    // 开始时间戳（音频帧 PTS，单位：AV_TIME_BASE）
    int64_t end_pts;      // 结束时间戳（可选，-1 表示未知）
    double start_time;    // 开始时间（秒）
    double end_time;      // 结束时间（秒，-1 表示未知）
} WhisperSubtitle;

VideoState *stream_open(const char *filename,const AVInputFormat *iformat);

void stream_close(VideoState *is);

void toggle_pause(VideoState *is);

void stream_seek(VideoState *is, int64_t pos, int64_t rel, int by_bytes);

double get_current_position(VideoState *is);

int64_t get_media_duration(VideoState *is);

/**
 * 设置动态音频滤镜
 * @param is VideoState 实例
 * @param filters 滤镜描述字符串，如 "whisper=model=/path/to/model.bin:language=zh"，传 NULL 清除滤镜
 * @return 0 成功，负值失败
 */
int set_audio_filters(VideoState *is, const char *filters);

/**
 * 请求重绘最后一帧（暂停时也生效）。
 * 运行时改了渲染参数（如 LUT）后调用，立即按新参数把当前画面重绘一次。
 */
void sky_request_video_redraw(VideoState *is);

#ifdef __cplusplus
};
#endif

#endif //MY_PLAYER_FFPLAY_H