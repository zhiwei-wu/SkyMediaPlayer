#include "sky_msg_queue.h"
#include <chrono>

// 定义消息队列最大大小
static const size_t MAX_MSG_SIZE = 1000;

SkyMessageQueue::SkyMessageQueue()
    : isRunning_(false)
    , isPaused_(false)
    , isDestroyed_(false)
    , shouldAbort_(false)
    , processThread_(nullptr)
    , messageHandler_(nullptr) {
}

SkyMessageQueue::~SkyMessageQueue() {
    destroy();
}

void SkyMessageQueue::start(std::function<void(const SkyMessage&)> handler) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load()) {
        return;
    }

    if (isRunning_.load()) {
        return;
    }

    messageHandler_ = handler;
    isRunning_.store(true);
    isPaused_.store(false);
    shouldAbort_.store(false);

    // 启动消息处理线程
    if (messageHandler_) {
        processThread_.reset(new std::thread(&SkyMessageQueue::messageProcessThread, this));
    }

    condition_.notify_all();
}

void SkyMessageQueue::pause() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load() || !isRunning_.load()) {
        return;
    }

    isPaused_.store(true);
    condition_.notify_all();
}

void SkyMessageQueue::abort() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load()) {
        return;
    }

    shouldAbort_.store(true);
    isRunning_.store(false);
    isPaused_.store(false);

    condition_.notify_all();

    // 等待处理线程结束
    if (processThread_ && processThread_->joinable()) {
        mutex_.unlock();
        processThread_->join();
        mutex_.lock();
        processThread_.reset();
    }
}

void SkyMessageQueue::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load()) {
        return;
    }

    isDestroyed_.store(true);
    shouldAbort_.store(true);
    isRunning_.store(false);
    isPaused_.store(false);

    // 清空消息队列
    while (!messageQueue_.empty()) {
        messageQueue_.pop();
    }

    condition_.notify_all();

    // 等待处理线程结束
    if (processThread_ && processThread_->joinable()) {
        mutex_.unlock();
        processThread_->join();
        mutex_.lock();
        processThread_.reset();
    }

    messageHandler_ = nullptr;
}

void SkyMessageQueue::flush() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load()) {
        return;
    }

    // 清空消息队列
    while (!messageQueue_.empty()) {
        messageQueue_.pop();
    }

    condition_.notify_all();
}

bool SkyMessageQueue::put(const SkyMessage& message) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_.load() || shouldAbort_.load()) {
        return false;
    }

    // 检查队列大小限制
    if (messageQueue_.size() >= MAX_MSG_SIZE) {
        return false;
    }
    
    messageQueue_.push(message);
    condition_.notify_one();
    
    return true;
}

bool SkyMessageQueue::get(SkyMessage& message, int timeoutMs) {
    return getInternal(message, timeoutMs);
}

bool SkyMessageQueue::getInternal(SkyMessage& message, int timeoutMs) {
    std::unique_lock<std::mutex> lock(mutex_);
    
    if (isDestroyed_.load()) {
        return false;
    }
    
    // 等待消息或超时
    if (timeoutMs > 0) {
        auto timeout = std::chrono::milliseconds(timeoutMs);
        bool result = condition_.wait_for(lock, timeout, [this] {
            return !messageQueue_.empty() || isDestroyed_.load() || shouldAbort_.load();
        });
        
        if (!result) {
            return false; // 超时
        }
    } else {
        condition_.wait(lock, [this] {
            return !messageQueue_.empty() || isDestroyed_.load() || shouldAbort_.load();
        });
    }
    
    if (isDestroyed_.load() || shouldAbort_.load()) {
        return false;
    }
    
    if (messageQueue_.empty()) {
        return false;
    }
    
    message = messageQueue_.front();
    messageQueue_.pop();
    
    return true;
}

int SkyMessageQueue::remove(int what) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (isDestroyed_.load()) {
        return 0;
    }
    
    int removedCount = 0;
    std::queue<SkyMessage> tempQueue;
    
    // 遍历队列，保留不需要删除的消息
    while (!messageQueue_.empty()) {
        SkyMessage msg = messageQueue_.front();
        messageQueue_.pop();
        
        if (what == -1 || msg.what == what) {
            removedCount++;
        } else {
            tempQueue.push(msg);
        }
    }
    
    // 将保留的消息放回队列
    messageQueue_ = std::move(tempQueue);
    
    condition_.notify_all();
    
    return removedCount;
}

size_t SkyMessageQueue::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return messageQueue_.size();
}

bool SkyMessageQueue::empty() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return messageQueue_.empty();
}

bool SkyMessageQueue::isRunning() const {
    return isRunning_.load();
}

bool SkyMessageQueue::isPaused() const {
    return isPaused_.load();
}

void SkyMessageQueue::messageProcessThread() {
    while (true) {
        SkyMessage message;
        
        // 检查是否应该退出
        if (isDestroyed_.load() || shouldAbort_.load()) {
            break;
        }
        
        // 检查是否暂停
        if (isPaused_.load()) {
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait(lock, [this] {
                return !isPaused_.load() || isDestroyed_.load() || shouldAbort_.load();
            });
            continue;
        }
        
        // 获取消息
        if (getInternal(message, 100)) { // 100ms超时
            // 处理消息
            if (messageHandler_) {
                try {
                    messageHandler_(message);
                } catch (...) {
                    // 忽略处理异常，继续处理下一个消息
                }
            }
        }
    }
}