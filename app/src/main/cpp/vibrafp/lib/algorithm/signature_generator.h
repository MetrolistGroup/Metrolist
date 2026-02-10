#ifndef LIB_ALGORITHM_SIGNATURE_GENERATOR_H_
#define LIB_ALGORITHM_SIGNATURE_GENERATOR_H_

#include "algorithm/signature.h"
#include "audio/downsampler.h"
#include "utils/fft.h"
#include "utils/ring_buffer.h"

constexpr std::size_t MAX_PEAKS = 255u;
constexpr std::size_t FFT_BUFFER_CHUNK_SIZE = 2048u;

class SignatureGenerator
{
public:
    SignatureGenerator();
    void FeedInput(const LowQualityTrack &input);
    Signature GetNextSignature();

    inline void AddSampleProcessed(std::uint32_t sample_processed)
    {
        sample_processed_ += sample_processed;
    }

    inline void set_max_time_seconds(double max_time_seconds)
    {
        max_time_seconds_ = max_time_seconds;
    }

private:
    void processInput(const LowQualityTrack &input);
    void doFFT(const LowQualityTrack &input);
    void doPeakSpreadingAndRecoginzation();
    void doPeakSpreading();
    void doPeakRecognition();
    void resetSignatureGenerater();

private:
    LowQualityTrack input_pending_processing_;
    std::uint32_t sample_processed_;
    double max_time_seconds_;

    fft::FFT<FFT_BUFFER_CHUNK_SIZE> fft_object_;
    Signature next_signature_;
    RingBuffer<std::int16_t> samples_ring_buffer_;
    RingBuffer<decltype(fft_object_)::FFTOutput> fft_outputs_;
    RingBuffer<decltype(fft_object_)::FFTOutput> spread_ffts_output_;
};

#endif // LIB_ALGORITHM_SIGNATURE_GENERATOR_H_
