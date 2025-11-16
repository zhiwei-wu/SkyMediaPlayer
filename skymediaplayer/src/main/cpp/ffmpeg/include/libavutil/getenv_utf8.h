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
 * License along with FFmpeg; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#ifndef AVUTIL_GETENV_UTF8_H
#define AVUTIL_GETENV_UTF8_H

/* Forward declarations to avoid including system headers */
#ifndef NULL
#define NULL ((void*)0)
#endif

/* Declare external functions we need */
extern char *getenv(const char *name);

/**
 * Get environment variable in UTF-8 encoding.
 * On non-Windows systems, this is just an alias for getenv().
 * On Windows, this converts from the system encoding to UTF-8.
 */
#ifdef _WIN32
char *getenv_utf8(const char *varname);
void freeenv_utf8(char *var);
void *fopen_utf8(const char *path, const char *mode);
#else
#define getenv_utf8 getenv
#define freeenv_utf8(x) ((void)0)
#define fopen_utf8 fopen
#endif

#endif /* AVUTIL_GETENV_UTF8_H */