#ifndef LIB_UTILS_RING_BUFFER_H_
#define LIB_UTILS_RING_BUFFER_H_

#include <vector>

template <typename T> class RingBuffer : private std::vector<T>
{
public:
    explicit RingBuffer(std::size_t size, T &&default_value = T());
    virtual ~RingBuffer();

    void Append(const T &value);
    std::uint32_t size() const
    {
        return std::vector<T>::size();
    }
    std::uint32_t &num_written()
    {
        return num_written_;
    }
    std::uint32_t &position()
    {
        return position_;
    }

    T &operator[](std::int32_t index);

    typename std::vector<T>::iterator begin()
    {
        return std::vector<T>::begin();
    }
    typename std::vector<T>::iterator end()
    {
        return std::vector<T>::end();
    }

private:
    std::uint32_t num_written_;
    std::uint32_t position_;
};

template <typename T> T &RingBuffer<T>::operator[](std::int32_t index)
{
    if (index < 0)
    {
        index = std::vector<T>::size() + index;
        // support negative index
    }
    return std::vector<T>::operator[](index);
}

template <typename T>
RingBuffer<T>::RingBuffer(std::size_t size, T &&default_value)
    : std::vector<T>(size, default_value), num_written_(0), position_(0)
{
}

template <typename T> RingBuffer<T>::~RingBuffer()
{
}

template <typename T> void RingBuffer<T>::Append(const T &value)
{
    this->operator[](position_) = value;
    position_ = (position_ + 1) % std::vector<T>::size();
    num_written_++;
}

#endif // LIB_UTILS_RING_BUFFER_H_
