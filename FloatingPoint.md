Floating-point audio

# Comparison of floating-point with integer or fixed-point #

Advantages of floating-point:

  * wider dynamic range
  * consistent accuracy across the dynamic range
  * more headroom to avoid clipping during intermediate calculations and transients

Disadvantages of floating-point:

  * uses more memory
  * surprising properties, for example addition is not associative
  * potential loss of arithmetic precision due to rounding or numerically unstable algorithms
  * requires greater understanding for accurate and reproducible results

Formerly, floating-point was notorious for being unavailable or slow.
This is still true on ultra low-end and embedded processors.
But processors on modern mobile devices now have hardware
floating-point with performance that is similar (or in some cases
even faster) than integer.
Modern CPUs also support [SIMD](http://en.wikipedia.org/wiki/SIMD)
(Single instruction, multiple data) which can improve performance further.

# Tips and Tricks #

A few simple tips and tricks:

  * Use "double" for infrequent calculations, e.g. when computing filter coefficients
  * Pay attention to the order of operations
  * Declare explicit variables for intermediate values
  * Use parentheses liberally
  * If you get a NaN or infinity result, use binary search to discover where it was introduced

## Application-level support for floating point audio data in Android SDK (using Java programming language) ##

The Android Developer website describes changes for
[Audio playback](http://developer.android.com/preview/api-overview.html#Multimedia)
in L developer preview.  There is a new audio format encoding
AudioFormat.ENCODING\_PCM\_FLOAT which is used similarly to
ENCODING\_PCM\_16\_BIT or ENCODING\_PCM\_8\_BIT for specifying AudioTrack data
formats. Corresponding there is an overloaded method AudioTrack.write()
that takes in a float array to deliver data.

```
   public int write(float[] audioData,
        int offsetInFloats,
        int sizeInFloats,
        int writeMode)
```

Please check the method signature in L developer preview, though actual
use in L developer preview will result in an IllegalArgumentException
since the Java hookup was not completed in time for the preview release.

# Links #

At Wikipedia:

  * [Audio bit depth](http://en.wikipedia.org/wiki/Audio_bit_depth)
  * [Floating point](http://en.wikipedia.org/wiki/Floating_point)
  * [IEEE 754 floating-point](http://en.wikipedia.org/wiki/IEEE_floating_point)
  * [Loss of significance](http://en.wikipedia.org/wiki/Loss_of_significance) (catastrophic cancellation)
  * [Numerical stability](https://en.wikipedia.org/wiki/Numerical_stability)

[What every computer scientist should know about floating-point arithmetic](http://docs.oracle.com/cd/E19957-01/806-3568/ncg_goldberg.html)
by David Goldberg, Xerox PARC (edited reprint)
