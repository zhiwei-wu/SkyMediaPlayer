#ifndef MY_PLAYER_SKY_MSG_QUEUE_H
#define MY_PLAYER_SKY_MSG_QUEUE_H

#include <queue>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <memory>
#include <thread>
#include <functional>
#include <chrono>
#include <cstddef>

// Include sky_messages.h to access message type definitions
#include "sky_messages.h"

/**
 * 消息类，用于在消息队列中传递数据
 */
class SkyMessage {
public:
    SkyMessage() : what(0), arg1(0), arg2(0), obj(nullptr) {}
    
    SkyMessage(int what, int arg1 = 0, int arg2 = 0, void* obj = nullptr)
        : what(what), arg1(arg1), arg2(arg2), obj(obj) {}
    
    // 拷贝构造函数
    SkyMessage(const SkyMessage& other)
        : what(other.what), arg1(other.arg1), arg2(other.arg2), obj(other.obj) {}
    
    // 赋值操作符
    SkyMessage& operator=(const SkyMessage& other) {
        if (this != &other) {
            what = other.what;
            arg1 = other.arg1;
            arg2 = other.arg2;
            obj = other.obj;
        }
        return *this;
    }
    
    // 移动构造函数
    SkyMessage(SkyMessage&& other) noexcept
        : what(other.what), arg1(other.arg1), arg2(other.arg2), obj(other.obj) {
        other.obj = nullptr;
    }
    
    // 移动赋值操作符
    SkyMessage& operator=(SkyMessage&& other) noexcept {
        if (this != &other) {
            what = other.what;
            arg1 = other.arg1;
            arg2 = other.arg2;
            obj = other.obj;
            other.obj = nullptr;
        }
        return *this;
    }

public:
    int what;    // 消息类型
    int arg1;    // 参数1
    int arg2;    // 参数2
    void* obj;   // 对象指针
};

/**
 * 线程安全的消息队列类
 */
class SkyMessageQueue {
public:
    SkyMessageQueue();
    ~SkyMessageQueue();
    
    // 禁止拷贝和赋值
    SkyMessageQueue(const SkyMessageQueue&) = delete;
    SkyMessageQueue& operator=(const SkyMessageQueue&) = delete;
    
    /**
     * 启动消息队列处理
     * @param handler 消息处理回调函数
     */
    void start(std::function<void(const SkyMessage&)> handler = nullptr);
    
    /**
     * 暂停消息队列处理
     */
    void pause();
    
    /**
     * 中止消息队列处理
     */
    void abort();
    
    /**
     * 销毁消息队列
     */
    void destroy();
    
    /**
     * 清空消息队列
     */
    void flush();
    
    /**
     * 向队列中添加消息
     * @param message 要添加的消息
     * @return 成功返回true，失败返回false
     */
    bool put(const SkyMessage& message);
    
    /**
     * 从队列中获取消息（阻塞）
     * @param message 输出参数，获取到的消息
     * @param timeoutMs 超时时间（毫秒），0表示无限等待
     * @return 成功返回true，超时或队列已销毁返回false
     */
    bool get(SkyMessage& message, int timeoutMs = 0);
    
    /**
     * 从队列中移除指定类型的消息
     * @param what 要移除的消息类型，-1表示移除所有消息
     * @return 移除的消息数量
     */
    int remove(int what = -1);
    
    /**
     * 获取队列中消息数量
     */
    size_t size() const;
    
    /**
     * 检查队列是否为空
     */
    bool empty() const;
    
    /**
     * 检查队列是否正在运行
     */
    bool isRunning() const;
    
    /**
     * 检查队列是否已暂停
     */
    bool isPaused() const;

private:
    /**
     * 消息处理线程函数
     */
    void messageProcessThread();
    
    /**
     * 内部获取消息的实现
     */
    bool getInternal(SkyMessage& message, int timeoutMs);

private:
    mutable std::mutex mutex_;                          // 互斥锁
    std::condition_variable condition_;                 // 条件变量
    std::queue<SkyMessage> messageQueue_;              // 消息队列
    
    std::atomic<bool> isRunning_;                      // 运行状态
    std::atomic<bool> isPaused_;                       // 暂停状态
    std::atomic<bool> isDestroyed_;                    // 销毁状态
    std::atomic<bool> shouldAbort_;                    // 中止标志
    
    std::unique_ptr<std::thread> processThread_;       // 处理线程
    std::function<void(const SkyMessage&)> messageHandler_; // 消息处理回调
};

#endif //MY_PLAYER_SKY_MSG_QUEUE_H