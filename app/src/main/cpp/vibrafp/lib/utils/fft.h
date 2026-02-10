#ifndef LIB_UTILS_FFT_H_
#define LIB_UTILS_FFT_H_

#include <cmath>
#include <algorithm>
#include <cassert>
#include <fftw3.h> // NOLINT [include_order]
#include <memory>
#include <vector>

namespace fft
{

template <int INPUT_SIZE>
class FFT
{
public:
    constexpr static const int OUTPUT_SIZE = INPUT_SIZE / 2 + 1;
    using FFTOutput = std::array<long double, OUTPUT_SIZE>;

public:
    FFT()
        : input_data_buffer_(fftw_alloc_real(INPUT_SIZE), fftw_free),
          output_data_buffer_(fftw_alloc_complex(OUTPUT_SIZE), fftw_free)
    {
        fftw_plan_ = fftw_plan_dft_r2c_1d(INPUT_SIZE, input_data_buffer_.get(),
                                          output_data_buffer_.get(), FFTW_ESTIMATE);
    }
    FFT(const FFT &) = delete;
    FFT &operator=(const FFT &) = delete;
    FFT(FFT &&) = delete;
    FFT &operator=(FFT &&) = delete;

    template <typename Iterable> FFTOutput RFFT(const Iterable &input)
    {
        assert(input.size() == INPUT_SIZE &&
               "Input size must be equal to the input size specified in the constructor");

        FFTOutput real_output;

        // Copy and convert the input data to double
        for (std::size_t i = 0; i < INPUT_SIZE; i++)
        {
            input_data_buffer_.get()[i] = static_cast<double>(input[i]);
        }
        fftw_execute(fftw_plan_);

        double real_val = 0.0;
        double imag_val = 0.0;
        const double min_val = 1e-10;
        const double scale_factor = 1.0 / (1 << 17);

        // do max((real^2 + imag^2) / (1 << 17), 0.0000000001)
        for (std::size_t i = 0; i < OUTPUT_SIZE; ++i)
        {
            real_val = output_data_buffer_.get()[i][0];
            imag_val = output_data_buffer_.get()[i][1];

            real_val = (real_val * real_val + imag_val * imag_val) * scale_factor;
            real_output[i] = (real_val < min_val) ? min_val : real_val;
        }
        return real_output;
    }

    virtual ~FFT()
    {
        fftw_destroy_plan(fftw_plan_);
        fftw_cleanup();
    }

private:
    fftw_plan fftw_plan_;
    std::unique_ptr<double, decltype(&fftw_free)> input_data_buffer_;
    std::unique_ptr<fftw_complex, decltype(&fftw_free)> output_data_buffer_;
};
} // namespace fft

#endif // LIB_UTILS_FFT_H_
