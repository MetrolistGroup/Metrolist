#ifndef LIB_ALGORITHM_SIGNATURE_H_
#define LIB_ALGORITHM_SIGNATURE_H_

#include <list>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include "algorithm/frequency.h"

// Prevent Structure Padding
#ifdef _MSC_VER
#pragma pack(push, 1)
#define PACKED_ATTRIBUTE
#else
#define PACKED_ATTRIBUTE __attribute__((packed))
#endif

struct RawSignatureHeader
{
    uint32_t magic1;
    uint32_t crc32;
    uint32_t size_minus_header;
    uint32_t magic2;
    uint32_t void1[3];
    uint32_t shifted_sample_rate_id;
    uint32_t void2[2];
    uint32_t number_samples_plus_divided_sample_rate;
    uint32_t fixed_value;
} PACKED_ATTRIBUTE;

#ifdef _MSC_VER
#pragma pack(pop)
#endif

class Signature
{
public:
    Signature(std::uint32_t sample_rate, std::uint32_t num_samples);
    ~Signature();
    void Reset(std::uint32_t sampleRate, std::uint32_t num_samples);

    inline void Addnum_samples(std::uint32_t num_samples)
    {
        num_samples_ += num_samples;
    }
    inline std::uint32_t sample_rate() const
    {
        return sample_rate_;
    }
    inline std::uint32_t num_samples() const
    {
        return num_samples_;
    }
    inline std::map<FrequencyBand, std::list<FrequencyPeak>> &frequency_band_to_peaks()
    {
        return frequency_band_to_peaks_;
    }
    std::uint32_t SumOfPeaksLength() const;
    std::string EncodeBase64() const;

private:
    template <typename T>
    std::stringstream &write_little_endian(std::stringstream &stream, const T &&value,
                                           size_t size = sizeof(T)) const
    {
        for (size_t i = 0; i < size; ++i)
        {
            stream << static_cast<char>(value >> (i << 3));
        }
        return stream;
    }

private:
    std::uint32_t sample_rate_;
    std::uint32_t num_samples_;
    std::map<FrequencyBand, std::list<FrequencyPeak>> frequency_band_to_peaks_;
};

#endif // LIB_ALGORITHM_SIGNATURE_H_
