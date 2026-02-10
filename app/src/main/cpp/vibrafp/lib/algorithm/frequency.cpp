#include "algorithm/frequency.h"
#include <cmath>

FrequencyPeak::FrequencyPeak(std::uint32_t fft_pass_number, std::uint32_t peak_magnitude,
                             std::uint32_t corrected_peak_frequency_bin, std::uint32_t sample_rate)
    : fft_pass_number_(fft_pass_number), peak_magnitude_(peak_magnitude),
      corrected_peak_frequency_bin_(corrected_peak_frequency_bin), sample_rate_(sample_rate)
{
}

FrequencyPeak::~FrequencyPeak()
{
}

double FrequencyPeak::ComputeFrequency() const
{
    return corrected_peak_frequency_bin_ * (static_cast<double>(sample_rate_) / 2. / 1024. / 64.);
}

double FrequencyPeak::ComputeAmplitudePCM() const
{
    return std::sqrt(std::exp((peak_magnitude_ - 6144) / 1477.3) * (1 << 17) / 2.) / 1024.;
}

double FrequencyPeak::ComputeElapsedSeconds() const
{
    return static_cast<double>(fft_pass_number_) * 128. / static_cast<double>(sample_rate_);
}
