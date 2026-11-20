# Software-decoder fixture

`eye-pair.h264` is an authored 32 by 16 pixel image, left half red and right half blue, encoded in full-range BT.709 as one baseline H.264 IDR picture.
It contains no camera image or personal data.
The decoder-choice test reads it with the pinned FreeRDP/FFmpeg software decoder after explicit permission and checks every pixel's eye identity.
Generate it with FFmpeg and libx264 in the isolated test environment:

```sh
ffmpeg -v error -f lavfi -i 'color=red:s=32x16,drawbox=x=16:y=0:w=16:h=16:color=blue:t=fill' \
  -vf 'scale=in_color_matrix=bt601:out_color_matrix=bt709:in_range=tv:out_range=pc' \
  -color_range pc -colorspace bt709 -frames:v 1 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -threads 4 \
  -x264-params 'keyint=1:threads=4' -f h264 eye-pair.h264
```

The test encoder is not linked into the receiver, whose FFmpeg configuration still disables GPL and non-free code.
