# Resamplers in L Developer Preview #

As of L Developer Preview, the audio resamplers are now entirely based
on FIR filters derived from a Kaiser windowed sinc function. There
are some good properties about the Kaiser windowed sinc:  it is
straightforward to calculate for its design parameters (stopband
ripple, transition bandwidth, cutoff frequency, filter length) and
nearly optimal for reduction of stopband energy compared to overall
energy. (See P.P. Vaidyanathan, Multirate Systems and Filter Banks, p. 50
for discussions of Kaiser Windows and its optimality and relationship
to Prolate Spheroidal Windows.)

The design parameters are automatically computed based on internal
quality determination and the sampling ratios desired. Based on the
design parameters, the windowed sinc filter is generated.  For music use,
the resampler for 44.1 to 48 kHz and vice versa is generated at a higher
quality than for arbitrary frequency conversion.

In L Developer Preview, we have both increased quality as well as speed
to achieve that quality.  But resamplers can introduce small amounts
of passband ripple and aliasing harmonic noise, and can cause some high
frequency loss in the transition band, so don’t use unnecessarily.

# Tips & Tricks #

## Prefer 44100 or 48000 to match device sampling rate ##
In general, it is best to choose the sampling rate to fit the device,
typically 44100 or 48000 in practice.  Use of a sample rate greater than
48000 will typically result in decreased quality as a resampler must be
used to play back the file.

## Prefer simple resampling ratios (Fixed versus Interpolated Polyphases) ##
The resampler operates either in fixed polyphase mode, where the
filter coefficients for each polyphase are precomputed; or interpolated
polyphase mode, where the filter coefficients for each polyphase must
be interpolated from the nearest two precomputed polyphases.

The resampler is fastest in fixed polyphase mode, when the ratio of input
rate over output rate L/M  (taking out the greatest common divisor),
has M less than 256.  For example, for 44100 to 48000 conversion, L = 147,
M = 160.  The improvement in performance for using a fixed polyphase
filter is ~35% (on Nexus 5, stereo 16 bit).

If you are in fixed polyphase mode, the sampling rate is locked for as
many samples converted, forever.  If you are in interpolated polyphase
mode, the sampling rate is approximate; the drift is generally on the
order of one sample per a few hours of playback on a 48 kHz device.
This is not usually a concern as approximation error is much less than
frequency error of internal quartz oscillators, and also thermal drift
or jitter (typically tens of ppm).

Hence, prefer simple-ratio sampling rates of 24 kHz (1:2), 32 kHz (2:3),
etc. when playing back on a 48 kHz device, even though other sampling
rates/ratios may be permitted through AudioTrack.

## Prefer upsampling to downsampling, especially when changing sample rates ##
Changing sampling rates is possible on-the-fly.  The granularity of
such change is based on the internal buffering (typically a few hundred
samples), not on a sample by sample basis.  This can be used for effects.

It is not recommended to dynamically change sampling rates when
downsampling.  When changing sample rates after an audio track is
created, differences of around 5-10 percent from the original rate may
trigger a filter recomputation when downsampling, to properly suppress
aliasing. This can take compute resources and may cause an audible click
if the filter is replaced on the fly.

## Limit downsampling to no more than 6:1 ##
Downsampling is typically triggered by hardware device requirements
at this time. When the Sample Rate converter is used for downsampling,
try to limit the downsampling ratio to no more than 6:1 for good aliasing
suppression. (e.g. no greater downsample than 48000 to 8000).  The filter
lengths adjust to match the downsampling ratio, but we sacrifice more
transition bandwidth at higher downsampling ratios to avoid excessively
increasing the filter length. There are no similar aliasing concerns for
upsampling.  Note that some parts of the audio pipeline in L developer
preview may prevent downsampling greater than 2:1.

## Don’t resample if you are concerned about latency ##
Use of a resampler prevents the track from being placed in the FastMixer
path, which means a significantly higher latency due to an additional
buffer (and its larger size) in the ordinary Mixer path. Furthermore,
there is an implicit delay from the filter length of the resampler,
though this is typically on the order of one millisecond or less,
not as large as the additional buffering for the ordinary Mixer path
(20 milliseconds typical).

# Other Resamplers (Short discussion) #

Equiripple filters generated from Parks McClellan filter design is
another choice for resamplers, but not done in L developer preview. (See
A. V. Oppenheim and R. W. Schafer, Discrete-time Signal Processing 3e,
pp. 554-570 for a detailed explanation of Equiripple filters and design.)
Its advantages are that (1) equiripple filters minimize the maximum
stopband ripple (minimax criteria, slightly different than the prolate
spheroid criteria) and (2) it allows the passband ripple to be specified
independently of the stopband ripple (the less important passband
ripple is generally equivalent to the stopband ripple for windowed
filter techniques).  Equiripple filters need to be precalculated as it
takes tens of seconds to compute for a several thousand tap filter even
on the faster Android devices.

Polynomial interpolators show up in imaging and also in graphics for
curve and fonts (where continuity of the line and its derivatives are
important), but they are not suitable for audio frequencies since the
stopband attenuation <~30dB is insufficient to prevent aliasing for 16
bit audio. (See R.E. Crochiere and L.R. Rabiner, Multirate Digital Signal
Processing, pp. 175-180 for discussions on polynomial interpolators use
in Signal Processing.)

# Online resources #
## Sample rates ##

[Sampling (signal processing)](http://en.wikipedia.org/wiki/Sampling_(signal_processing)) at Wikipedia

## Resampling ##

[Sample rate conversion](http://en.wikipedia.org/wiki/Sample_rate_conversion) at Wikipedia

[Sample Rate Conversion](http://source.android.com/devices/audio_src.html) at source.android.com

## The high bit-depth and high kHz controversy ##

[24/192 Music Downloads ... and why they make no sense](http://people.xiph.org/~xiphmont/demo/neil-young.html)
by Christopher "Monty" Montgomery of Xiph.Org

[D/A and A/D | Digital Show and Tell](https://www.youtube.com/watch?v=cIQ9IXSUzuM)
video by Christopher "Monty" Montgomery of Xiph.Org

[The Science of Sample Rates (When Higher Is Better — And When It Isn’t)](http://www.trustmeimascientist.com/2013/02/04/the-science-of-sample-rates-when-higher-is-better-and-when-it-isnt/)

[Audio Myths & DAW Wars](http://www.image-line.com/support/FLHelp/html/app_audio.htm)

[The Emperor's New Sampling Rate](http://mixonline.com/recording/mixing/audio_emperors_new_sampling/)

[192kHz/24bit vs. 96kHz/24bit "debate"- Interesting revelation](http://forums.stevehoffman.tv/threads/192khz-24bit-vs-96khz-24bit-debate-interesting-revelation.317660/)

[Hearing Disability Assessment](http://www.dohc.ie/publications/pdf/hearing.pdf?direct=1)
Report of the Expert Hearing Group, Published by the Department of Health and Children © 1998