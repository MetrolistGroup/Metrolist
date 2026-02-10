#include "audio/downsampler.h"
#include <cstring>
#include <algorithm>
#include <map>
#include <tuple>
#include "audio/byte_control.h"
#include "audio/wav.h"

LowQualityTrack Downsampler::GetLowQualityPCM(const Wav &wav, std::int32_t start_sec,
                                              std::int32_t end_sec)
{
    LowQualityTrack low_quality_pcm;

    const auto channels = wav.num_channels();
    const auto sample_rate = wav.sample_rate_();
    const auto bits_per_sample = wav.bits_per_sample();
    const auto data_size = wav.data_size();
    const auto audio_format = wav.audio_format();
    const std::uint8_t *pcm_data = wav.data().get();

    if (channels == 1 && sample_rate == LOW_QUALITY_SAMPLE_RATE &&
        bits_per_sample == LOW_QUALITY_SAMPLE_BIT_WIDTH && start_sec == 0 && end_sec == -1)
    {
        // no need to convert low quality pcm. just copy raw data
        low_quality_pcm.resize(data_size);
        std::memcpy(low_quality_pcm.data(), wav.data().get(), data_size);
        return low_quality_pcm;
    }

    double downsample_ratio = sample_rate / static_cast<double>(LOW_QUALITY_SAMPLE_RATE);
    std::uint32_t width = bits_per_sample / 8;
    std::uint32_t sample_count = data_size / width;

    const void *src_raw_data = pcm_data + (start_sec * sample_rate * width * channels);

    std::uint32_t new_sample_count = sample_count / channels / downsample_ratio;

    if (end_sec != -1)
    {
        new_sample_count = (end_sec - start_sec) * LOW_QUALITY_SAMPLE_RATE;
    }

    low_quality_pcm.resize(new_sample_count);

    auto downsample_func = &Downsampler::signedMonoToMono;
    bool is_signed = audio_format == 1;

    downsample_func = getDownsampleFunc(is_signed, bits_per_sample, channels);
    downsample_func(&low_quality_pcm, src_raw_data, downsample_ratio, new_sample_count, width,
                    channels);
    return low_quality_pcm;
}

DownsampleFunc Downsampler::getDownsampleFunc(bool is_signed, std::uint32_t width,
                                              std::uint32_t channels)
{
    channels = std::min(channels, 3u);
    width = is_signed ? 0 : width;

    static std::map<std::tuple<bool, std::uint32_t, std::uint32_t>, DownsampleFunc> func_map{
        {std::make_tuple(true, 0, 1), &Downsampler::signedMonoToMono},
        {std::make_tuple(true, 0, 2), &Downsampler::signedStereoToMono},
        {std::make_tuple(true, 0, 3), &Downsampler::signedMultiToMono},
        {std::make_tuple(false, 32, 1), &Downsampler::floatMonoToMono<float>},
        {std::make_tuple(false, 32, 2), &Downsampler::floatStereoToMono<float>},
        {std::make_tuple(false, 32, 3), &Downsampler::floatMultiToMono<float>},
        {std::make_tuple(false, 64, 1), &Downsampler::floatMonoToMono<double>},
        {std::make_tuple(false, 64, 2), &Downsampler::floatStereoToMono<double>},
        {std::make_tuple(false, 64, 3), &Downsampler::floatMultiToMono<double>},
    };
    return func_map.at(std::make_tuple(is_signed, width, channels));
}

void Downsampler::signedMonoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                   std::uint32_t new_sample_count, std::uint32_t width,
                                   std::uint32_t channels)
{
    std::uint32_t index = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        index = std::uint32_t(i * downsample_ratio) * width * channels;
        dst->at(i) = GETSAMPLE64(width, src, index) >> (64 - LOW_QUALITY_SAMPLE_BIT_WIDTH);
    }
}

void Downsampler::signedStereoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                     std::uint32_t new_sample_count, std::uint32_t width,
                                     std::uint32_t channels)
{
    std::uint32_t index = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        index = std::uint32_t(i * downsample_ratio) * width * channels;
        LowQualitySample sample1 =
            GETSAMPLE64(width, src, index) >> (64 - LOW_QUALITY_SAMPLE_BIT_WIDTH);
        LowQualitySample sample2 =
            GETSAMPLE64(width, src, index + width) >> (64 - LOW_QUALITY_SAMPLE_BIT_WIDTH);
        dst->at(i) = (sample1 + sample2) / 2;
    }
}

void Downsampler::signedMultiToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                    std::uint32_t new_sample_count, std::uint32_t width,
                                    std::uint32_t channels)
{
    double collected_sample = 0;
    std::uint32_t index = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        collected_sample = 0;
        for (std::uint32_t k = 0; k < channels; k++)
        {
            index = std::uint32_t(i * downsample_ratio) * width * channels;
            collected_sample +=
                GETSAMPLE64(width, src, index + k * width) >> (64 - LOW_QUALITY_SAMPLE_BIT_WIDTH);
        }
        dst->at(i) = LowQualitySample(collected_sample / channels);
    }
}

template <typename T>
void Downsampler::floatMonoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                  std::uint32_t new_sample_count, std::uint32_t width,
                                  std::uint32_t channels)
{
    T temp_float_sample = 0;
    std::uint64_t temp_sample = 0;
    std::uint32_t index = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        index = std::uint32_t(i * downsample_ratio) * width * channels;
        temp_sample = GETSAMPLE64(width, src, index) >> (64 - sizeof(T) * 8);
        temp_float_sample = *reinterpret_cast<T *>(&temp_sample);
        dst->at(i) = LowQualitySample(temp_float_sample * LOW_QUALITY_SAMPLE_MAX);
    }
}

template <typename T>
void Downsampler::floatStereoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                    std::uint32_t new_sample_count, std::uint32_t width,
                                    std::uint32_t channels)
{
    std::uint64_t temp_sample1 = 0;
    std::uint64_t temp_sample2 = 0;
    T temp_float_sample1 = 0;
    T temp_float_sample2 = 0;
    std::uint32_t index = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        index = std::uint32_t(i * downsample_ratio) * width * channels;
        temp_sample1 = GETSAMPLE64(width, src, index) >> (64 - sizeof(T) * 8);
        temp_sample2 = GETSAMPLE64(width, src, index + width) >> (64 - sizeof(T) * 8);
        temp_float_sample1 = *reinterpret_cast<T *>(&temp_sample1);
        temp_float_sample2 = *reinterpret_cast<T *>(&temp_sample2);
        dst->at(i) = LowQualitySample((temp_float_sample1 + temp_float_sample2) / 2 *
                                      LOW_QUALITY_SAMPLE_MAX);
    }
}

template <typename T>
void Downsampler::floatMultiToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                   std::uint32_t new_sample_count, std::uint32_t width,
                                   std::uint32_t channels)
{
    std::uint32_t index = 0;
    T collected_sample = 0;
    std::uint64_t temp_sample = 0;
    for (std::uint32_t i = 0; i < new_sample_count; i++)
    {
        collected_sample = 0;
        index = std::uint32_t(i * downsample_ratio) * width * channels;
        for (std::uint32_t k = 0; k < channels; k++)
        {
            temp_sample = GETSAMPLE64(width, src, index + k * width) >> (64 - sizeof(T) * 8);
            collected_sample += *reinterpret_cast<T *>(&temp_sample);
        }
        dst->at(i) = LowQualitySample(collected_sample / channels * LOW_QUALITY_SAMPLE_MAX);
    }
}
