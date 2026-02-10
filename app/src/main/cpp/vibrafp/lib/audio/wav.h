#ifndef LIB_AUDIO_WAV_H_
#define LIB_AUDIO_WAV_H_

#include <memory>
#include <string>
#include "audio/byte_control.h"

struct WavHeader
{
    char riff_header[4]; // "RIFF"
    std::uint32_t file_size;
    char wave_header[4]; // "WAVE"
};

struct FmtSubchunk
{
    std::uint16_t audio_format; // 1 = PCM, 3 = IEEE float, etc.
    std::uint16_t num_channels;
    std::uint32_t sample_rate;
    std::uint32_t byte_rate;
    std::uint16_t block_align;
    std::uint16_t bits_per_sample;
};

enum class AudioFormat
{
    PCM_INTEGER = 1,
    PCM_FLOAT = 3,
};

class Wav
{
public:
    Wav(Wav &&) = default;
    Wav(const Wav &) = delete;
    static Wav FromFile(const std::string &wav_file_path);
    static Wav FromRawWav(const char *raw_wav, std::uint32_t raw_wav_size);
    static Wav FromSignedPCM(const char *raw_pcm, std::uint32_t raw_pcm_size,
                             std::uint32_t sample_rate, std::uint32_t sample_width,
                             std::uint32_t channel_count);
    static Wav FromFloatPCM(const char *raw_pcm, std::uint32_t raw_pcm_size,
                            std::uint32_t sample_rate, std::uint32_t sample_width,
                            std::uint32_t channel_count);
    ~Wav();

    inline std::uint16_t audio_format() const
    {
        return fmt_.audio_format;
    }
    inline std::uint16_t num_channels() const
    {
        return fmt_.num_channels;
    }
    inline std::uint32_t sample_rate_() const
    {
        return fmt_.sample_rate;
    }
    inline std::uint32_t bits_per_sample() const
    {
        return fmt_.bits_per_sample;
    }
    inline std::uint32_t data_size() const
    {
        return data_size_;
    }
    inline std::uint32_t file_size() const
    {
        return header_.file_size;
    }
    inline const std::unique_ptr<std::uint8_t[]> &data() const
    {
        return data_;
    }

private:
    Wav() = default;
    static Wav fromPCM(const char *raw_pcm, std::uint32_t raw_pcm_size, AudioFormat audio_format,
                       std::uint32_t sample_rate, std::uint32_t sample_width,
                       std::uint32_t channel_count);
    void readWavFileBuffer(std::istream &stream);

private:
    WavHeader header_;
    FmtSubchunk fmt_;
    std::string wav_file_path_;
    std::uint32_t data_size_;
    std::unique_ptr<std::uint8_t[]> data_;
};

#endif // LIB_AUDIO_WAV_H_
