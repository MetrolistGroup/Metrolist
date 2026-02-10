#include "../include/vibra.h"
#include "algorithm/signature_generator.h"
#include "audio/downsampler.h"
#include "audio/wav.h"

constexpr std::uint32_t MAX_DURATION_SECONDS = 12;

Fingerprint *_get_fingerprint_from_wav(const Wav &wav);

Fingerprint *_get_fingerprint_from_low_quality_pcm(const LowQualityTrack &pcm);

Fingerprint *vibra_get_fingerprint_from_wav_data(const char *raw_wav, int wav_data_size)
{
    Wav wav = Wav::FromRawWav(raw_wav, wav_data_size);
    return _get_fingerprint_from_wav(wav);
}

Fingerprint *vibra_get_fingerprint_from_signed_pcm(const char *raw_pcm, int pcm_data_size,
                                                   int sample_rate, int sample_width,
                                                   int channel_count)
{
    Wav wav = Wav::FromSignedPCM(raw_pcm, pcm_data_size, sample_rate, sample_width, channel_count);
    return _get_fingerprint_from_wav(wav);
}

Fingerprint *vibra_get_fingerprint_from_float_pcm(const char *raw_pcm, int pcm_data_size,
                                                  int sample_rate, int sample_width,
                                                  int channel_count)
{
    Wav wav = Wav::FromFloatPCM(raw_pcm, pcm_data_size, sample_rate, sample_width, channel_count);
    return _get_fingerprint_from_wav(wav);
}

const char *vibra_get_uri_from_fingerprint(Fingerprint *fingerprint)
{
    return fingerprint->uri.c_str();
}

unsigned int vibra_get_sample_ms_from_fingerprint(Fingerprint *fingerprint)
{
    return fingerprint->sample_ms;
}

void vibra_free_fingerprint(Fingerprint *fingerprint)
{
    delete fingerprint;
}

Fingerprint *_get_fingerprint_from_wav(const Wav &wav)
{
    LowQualityTrack pcm = Downsampler::GetLowQualityPCM(wav);
    return _get_fingerprint_from_low_quality_pcm(pcm);
}

Fingerprint *_get_fingerprint_from_low_quality_pcm(const LowQualityTrack &pcm)
{
    SignatureGenerator generator;
    generator.FeedInput(pcm);
    generator.set_max_time_seconds(MAX_DURATION_SECONDS);

    Signature signature = generator.GetNextSignature();

    Fingerprint *fingerprint = new Fingerprint;
    fingerprint->uri = signature.EncodeBase64();
    fingerprint->sample_ms = signature.num_samples() * 1000 / signature.sample_rate();
    return fingerprint;
}
