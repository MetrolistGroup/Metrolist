#ifndef LIB_ALGORITHM_FREQUENCY_H_
#define LIB_ALGORITHM_FREQUENCY_H_

#include <cstdint>

enum class FrequencyBand
{
    _0_150 = -1,
    _250_520,
    _520_1450,
    _1450_3500,
    _3500_5500,
};

class FrequencyPeak
{
public:
    FrequencyPeak(std::uint32_t fft_pass_number, std::uint32_t peak_magnitude,
                  std::uint32_t corrected_peak_frequency_bin, std::uint32_t sample_rate);
    ~FrequencyPeak();

    inline std::uint32_t fft_pass_number() const
    {
        return fft_pass_number_;
    }
    inline std::uint32_t peak_magnitude() const
    {
        return peak_magnitude_;
    }
    inline std::uint32_t corrected_peak_frequency_bin() const
    {
        return corrected_peak_frequency_bin_;
    }
    inline double ComputeFrequency() const;
    inline double ComputeAmplitudePCM() const;
    inline double ComputeElapsedSeconds() const;

private:
    std::uint32_t fft_pass_number_;
    std::uint32_t peak_magnitude_;
    std::uint32_t corrected_peak_frequency_bin_;
    std::uint32_t sample_rate_;
};

#endif // LIB_ALGORITHM_FREQUENCY_H_
