/*
 * Copyright (c) 2024
 *
 * This file is part of FFmpeg.
 *
 * FFmpeg is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * FFmpeg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with FFmpeg; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#ifndef AVUTIL_LIBM_H
#define AVUTIL_LIBM_H

/* Math constants and compatibility macros */

#ifndef M_E
#define M_E            2.7182818284590452354   /* e */
#endif

#ifndef M_LN2
#define M_LN2          0.69314718055994530942  /* log_e 2 */
#endif

#ifndef M_LN10
#define M_LN10         2.30258509299404568402  /* log_e 10 */
#endif

#ifndef M_LOG2_10
#define M_LOG2_10      3.32192809488736234787  /* log_2 10 */
#endif

#ifndef M_PHI
#define M_PHI          1.61803398874989484820   /* phi / golden ratio */
#endif

#ifndef M_PI
#define M_PI           3.14159265358979323846  /* pi */
#endif

#ifndef M_PI_2
#define M_PI_2         1.57079632679489661923  /* pi/2 */
#endif

#ifndef M_SQRT1_2
#define M_SQRT1_2      0.70710678118654752440  /* 1/sqrt(2) */
#endif

#ifndef M_SQRT2
#define M_SQRT2        1.41421356237309504880  /* sqrt(2) */
#endif

#ifndef NAN
#define NAN            (0.0/0.0)
#endif

#ifndef INFINITY
#define INFINITY       (1.0/0.0)
#endif

/* Function compatibility macros */
#if !defined(isfinite) && !defined(__ANDROID__)
#define isfinite(x) ((x) == (x) && (x) != INFINITY && (x) != -INFINITY)
#endif

#if !defined(isinf) && !defined(__ANDROID__)
#define isinf(x) ((x) == INFINITY || (x) == -INFINITY)
#endif

#if !defined(isnan) && !defined(__ANDROID__)
#define isnan(x) ((x) != (x))
#endif

#endif /* AVUTIL_LIBM_H */