#ifndef LIB_AUDIO_DOWNSAMPLER_H_
#define LIB_AUDIO_DOWNSAMPLER_H_

#include <cstdint>
#include <vector>

// forward declaration
class Wav;
//

using LowQualitySample = std::int16_t;
using LowQualityTrack = std::vector<LowQualitySample>;

constexpr std::uint32_t LOW_QUALITY_SAMPLE_RATE = 16000;
constexpr std::uint32_t LOW_QUALITY_SAMPLE_BIT_WIDTH = sizeof(LowQualitySample) * 8;
constexpr std::uint32_t LOW_QUALITY_SAMPLE_MAX = 32767;

using DownsampleFunc = void (*)(LowQualityTrack *, const void *, double, std::uint32_t,
                                std::uint32_t, std::uint32_t);

class Downsampler
{
public:
    static LowQualityTrack GetLowQualityPCM(const Wav &wav, std::int32_t start_sec = 0,
                                            std::int32_t end_sec = -1);

private:
    static DownsampleFunc getDownsampleFunc(bool is_signed, std::uint32_t width,
                                            std::uint32_t channels);

    static void signedStereoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                   std::uint32_t new_sample_count, std::uint32_t width,
                                   std::uint32_t channels);
    static void signedMonoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                 std::uint32_t new_sample_count, std::uint32_t width,
                                 std::uint32_t channels);
    static void signedMultiToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                  std::uint32_t new_sample_count, std::uint32_t width,
                                  std::uint32_t channels);

    template <typename T>
    static void floatStereoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                  std::uint32_t new_sample_count, std::uint32_t width,
                                  std::uint32_t channels);
    template <typename T>
    static void floatMonoToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                std::uint32_t new_sample_count, std::uint32_t width,
                                std::uint32_t channels);
    template <typename T>
    static void floatMultiToMono(LowQualityTrack *dst, const void *src, double downsample_ratio,
                                 std::uint32_t new_sample_count, std::uint32_t width,
                                 std::uint32_t channels);
};

#endif // LIB_AUDIO_DOWNSAMPLER_H_
