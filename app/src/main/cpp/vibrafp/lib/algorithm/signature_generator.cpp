#include "algorithm/signature_generator.h"
#include <algorithm>
#include <array>
#include <iostream>
#include <list>
#include <numeric>
#include <vector>
#include <utility>
#include "utils/hanning.h"

SignatureGenerator::SignatureGenerator()
    : input_pending_processing_(), sample_processed_(0), max_time_seconds_(3.1),
      next_signature_(16000, 0), samples_ring_buffer_(FFT_BUFFER_CHUNK_SIZE, 0),
      fft_outputs_(256, {0.0}), spread_ffts_output_(256, {0.0})
{
}

void SignatureGenerator::FeedInput(const LowQualityTrack &input)
{
    input_pending_processing_.reserve(input_pending_processing_.size() + input.size());
    input_pending_processing_.insert(input_pending_processing_.end(), input.begin(), input.end());
}

Signature SignatureGenerator::GetNextSignature()
{
    if (input_pending_processing_.size() - sample_processed_ < 128)
    {
        throw std::runtime_error("Not enough input to generate signature");
    }

    double num_samples = static_cast<double>(next_signature_.num_samples());
    while (input_pending_processing_.size() - sample_processed_ >= 128 &&
           (num_samples / next_signature_.sample_rate() < max_time_seconds_ ||
            next_signature_.SumOfPeaksLength() < MAX_PEAKS))
    {
        LowQualityTrack input(input_pending_processing_.begin() + sample_processed_,
                              input_pending_processing_.begin() + sample_processed_ + 128);

        processInput(input);
        sample_processed_ += 128;
        num_samples = static_cast<double>(next_signature_.num_samples());
    }

    Signature result = std::move(next_signature_);
    resetSignatureGenerater();
    return result; // RVO
}

void SignatureGenerator::processInput(const LowQualityTrack &input)
{
    next_signature_.Addnum_samples(input.size());
    for (std::size_t chunk = 0; chunk < input.size(); chunk += 128)
    {
        LowQualityTrack chunk_input(input.begin() + chunk, input.begin() + chunk + 128);

        doFFT(chunk_input);
        doPeakSpreadingAndRecoginzation();
    }
}

void SignatureGenerator::doFFT(const LowQualityTrack &input)
{
    std::copy(input.begin(), input.end(),
              samples_ring_buffer_.begin() + samples_ring_buffer_.position());

    samples_ring_buffer_.position() += input.size();
    samples_ring_buffer_.position() %= FFT_BUFFER_CHUNK_SIZE;
    samples_ring_buffer_.num_written() += input.size();

    std::vector<long double> excerpt_from_ring_buffer(FFT_BUFFER_CHUNK_SIZE, 0.0);

    std::copy(samples_ring_buffer_.begin() + samples_ring_buffer_.position(),
              samples_ring_buffer_.end(), excerpt_from_ring_buffer.begin());

    std::copy(samples_ring_buffer_.begin(),
              samples_ring_buffer_.begin() + samples_ring_buffer_.position(),
              excerpt_from_ring_buffer.begin() + FFT_BUFFER_CHUNK_SIZE -
                  samples_ring_buffer_.position());

    for (std::size_t i = 0; i < FFT_BUFFER_CHUNK_SIZE; ++i)
    {
        excerpt_from_ring_buffer[i] *= HANNIG_MATRIX[i];
    }

    decltype(fft_object_)::FFTOutput real = fft_object_.RFFT(excerpt_from_ring_buffer);
    fft_outputs_.Append(real);
}

void SignatureGenerator::doPeakSpreadingAndRecoginzation()
{
    doPeakSpreading();

    if (spread_ffts_output_.num_written() >= 47)
    {
        doPeakRecognition();
    }
}

void SignatureGenerator::doPeakSpreading()
{
    auto spread_last_fft = fft_outputs_[fft_outputs_.position() - 1];

    for (auto position = 0u; position < decltype(fft_object_)::OUTPUT_SIZE; ++position)
    {
        if (position < decltype(fft_object_)::OUTPUT_SIZE - 2)
        {
            spread_last_fft[position] = *std::max_element(spread_last_fft.begin() + position,
                                                          spread_last_fft.begin() + position + 3);
        }

        auto max_value = spread_last_fft[position];
        for (auto former_fft_num : {-1, -3, -6})
        {
            auto &former_fft_ouput =
                spread_ffts_output_[(spread_ffts_output_.position() + former_fft_num) %
                                  spread_ffts_output_.size()];
            former_fft_ouput[position] = max_value =
                std::max(max_value, former_fft_ouput[position]);
        }
    }
    spread_ffts_output_.Append(spread_last_fft);
}

void SignatureGenerator::doPeakRecognition()
{
    const auto &fft_minus_46 = fft_outputs_[(fft_outputs_.position() - 46) % fft_outputs_.size()];
    const auto &fft_minus_49 =
        spread_ffts_output_[(spread_ffts_output_.position() - 49) % spread_ffts_output_.size()];

    auto other_offsets = {-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249};
    for (auto bin_position = 10u; bin_position < decltype(fft_object_)::OUTPUT_SIZE - 8; ++bin_position)
    {
        if (fft_minus_46[bin_position] >= 1.0 / 64.0 &&
            fft_minus_46[bin_position] >= fft_minus_49[bin_position])
        {
            auto max_neighbor_in_fft_minus_49 = 0.0l;
            for (auto neighbor_offset : {-10, -7, -4, -3, 1, 2, 5, 8})
            {
                max_neighbor_in_fft_minus_49 = std::max(
                    max_neighbor_in_fft_minus_49, fft_minus_49[bin_position + neighbor_offset]);
            }

            if (fft_minus_46[bin_position] > max_neighbor_in_fft_minus_49)
            {
                auto max_neighbor_in_other_adjacent_ffts = max_neighbor_in_fft_minus_49;
                for (auto other_offset : other_offsets)
                {
                    max_neighbor_in_other_adjacent_ffts = std::max(
                        max_neighbor_in_other_adjacent_ffts,
                        spread_ffts_output_[(spread_ffts_output_.position() + other_offset) %
                                            spread_ffts_output_.size()][bin_position - 1]);
                }

                if (fft_minus_46[bin_position] > max_neighbor_in_other_adjacent_ffts)
                {
                    auto fft_number = spread_ffts_output_.num_written() - 46;
                    auto peak_magnitude =
                        std::log(std::max(1.0l / 64, fft_minus_46[bin_position])) * 1477.3 + 6144;
                    auto peak_magnitude_before =
                        std::log(std::max(1.0l / 64, fft_minus_46[bin_position - 1])) * 1477.3 +
                        6144;
                    auto peak_magnitude_after =
                        std::log(std::max(1.0l / 64, fft_minus_46[bin_position + 1])) * 1477.3 +
                        6144;

                    auto peak_variation_1 =
                        peak_magnitude * 2 - peak_magnitude_before - peak_magnitude_after;
                    auto peak_variation_2 =
                        (peak_magnitude_after - peak_magnitude_before) * 32 / peak_variation_1;

                    auto corrected_peak_frequency_bin = bin_position * 64.0 + peak_variation_2;
                    auto frequency_hz =
                        corrected_peak_frequency_bin * (16000.0l / 2. / 1024. / 64.);

                    auto band = FrequencyBand();
                    if (frequency_hz < 250)
                        continue;
                    else if (frequency_hz < 520)
                        band = FrequencyBand::_250_520;
                    else if (frequency_hz < 1450)
                        band = FrequencyBand::_520_1450;
                    else if (frequency_hz < 3500)
                        band = FrequencyBand::_1450_3500;
                    else if (frequency_hz <= 5500)
                        band = FrequencyBand::_3500_5500;
                    else
                        continue;

                    auto &band_to_sound_peaks = next_signature_.frequency_band_to_peaks();
                    if (band_to_sound_peaks.find(band) == band_to_sound_peaks.end())
                    {
                        band_to_sound_peaks[band] = std::list<FrequencyPeak>();
                    }

                    band_to_sound_peaks[band].push_back(
                        FrequencyPeak(fft_number, static_cast<std::int32_t>(peak_magnitude),
                                      static_cast<std::int32_t>(corrected_peak_frequency_bin),
                                      LOW_QUALITY_SAMPLE_RATE));
                }
            }
        }
    }
}

void SignatureGenerator::resetSignatureGenerater()
{
    next_signature_ = Signature(16000, 0);
    samples_ring_buffer_ = RingBuffer<std::int16_t>(FFT_BUFFER_CHUNK_SIZE, 0);
    fft_outputs_ = RingBuffer<decltype(fft_object_)::FFTOutput>(256, {0.0});
    spread_ffts_output_ = RingBuffer<decltype(fft_object_)::FFTOutput>(256, {0.0});
}
