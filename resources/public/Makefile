#**************************************************************************************************
#
#   raylib makefile for Desktop platforms, Raspberry Pi, Android and HTML5
#
#   Copyright (c) 2013-2023 Ramon Santamaria (@raysan5)
#
#   This software is provided "as-is", without any express or implied warranty. In no event
#   will the authors be held liable for any damages arising from the use of this software.
#
#   Permission is granted to anyone to use this software for any purpose, including commercial
#   applications, and to alter it and redistribute it freely, subject to the following restrictions:
#
#     1. The origin of this software must not be misrepresented; you must not claim that you
#     wrote the original software. If you use this software in a product, an acknowledgment
#     in the product documentation would be appreciated but is not required.
#
#     2. Altered source versions must be plainly marked as such, and must not be misrepresented
#     as being the original software.
#
#     3. This notice may not be removed or altered from any source distribution.
#
#**************************************************************************************************

.PHONY: all clean clean-all

# Define required environment variables
#------------------------------------------------------------------------------------------------
# Define target platform: PLATFORM_DESKTOP, PLATFORM_RPI, PLATFORM_DRM, PLATFORM_ANDROID, PLATFORM_WEB
PLATFORM              ?= PLATFORM_WEB

# Define project variables
PROJECT_NAME          ?= raylib_game
PROJECT_VERSION       ?= 1.0
PROJECT_BUILD_PATH    ?= .

RAYLIB_PATH           ?= /raylib

# Locations of raylib.h and libraylib.a/libraylib.so

# Library type compilation: STATIC (.a) or SHARED (.so/.dll)
RAYLIB_LIBTYPE        ?= STATIC

# Build mode for project: DEBUG or RELEASE
BUILD_MODE            ?= RELEASE

# PLATFORM_WEB: Default properties
BUILD_WEB_ASYNCIFY    ?= FALSE
BUILD_WEB_SHELL       ?= minshell.html
BUILD_WEB_HEAP_SIZE   ?= 134217728
BUILD_WEB_RESOURCES   ?= TRUE
BUILD_WEB_RESOURCES_PATH  ?= resources

PLATFORM_OS = LINUX

ifndef PLATFORM_SHELL
		PLATFORM_SHELL = sh
endif

# Define raylib release directory for compiled library
RAYLIB_RELEASE_PATH 	?= $(RAYLIB_PATH)/src

# Define default C compiler: CC
#------------------------------------------------------------------------------------------------

# HTML5 emscripten compiler
# WARNING: To compile to HTML5, code must be redesigned
# to use emscripten.h and emscripten_set_main_loop()
CC = emcc

# Define default make program: MAKE
#------------------------------------------------------------------------------------------------
MAKE ?= make

# Define compiler flags: CFLAGS
#------------------------------------------------------------------------------------------------
#  -O1                  defines optimization level
#  -g                   include debug information on compilation
#  -s                   strip unnecessary data from build
#  -Wall                turns on most, but not all, compiler warnings
#  -std=c99             defines C language mode (standard C from 1999 revision)
#  -std=gnu99           defines C language mode (GNU C from 1999 revision)
#  -Wno-missing-braces  ignore invalid warning (GCC bug 53119)
#  -Wno-unused-value    ignore unused return values of some functions (i.e. fread())
#  -D_DEFAULT_SOURCE    use with -std=c99 on Linux and PLATFORM_WEB, required for timespec
CFLAGS = -std=c99 -Wall -Wno-missing-braces -Wunused-result -D_DEFAULT_SOURCE

ifeq ($(BUILD_MODE),DEBUG)
    CFLAGS += -g -D_DEBUG
else
		ifeq ($(BUILD_WEB_ASYNCIFY),TRUE)
				CFLAGS += -O3
		else
				CFLAGS += -Os
		endif
endif

# Define include paths for required headers: INCLUDE_PATHS
# NOTE: Some external/extras libraries could be required (stb, physac, easings...)
#------------------------------------------------------------------------------------------------
INCLUDE_PATHS = -I. -I$(RAYLIB_PATH)/src -I$(RAYLIB_PATH)/src/external -I$(RAYLIB_PATH)/src/extras

# Define additional directories containing required header files
INCLUDE_PATHS += -I$(EMSCRIPTEN_PATH)/cache/sysroot/include

# Define library paths containing required libs: LDFLAGS
#------------------------------------------------------------------------------------------------
LDFLAGS = -L. -L$(RAYLIB_RELEASE_PATH) -L$(RAYLIB_PATH)/src

# -Os                        # size optimization
# -O2                        # optimization level 2, if used, also set --memory-init-file 0
# -s USE_GLFW=3              # Use glfw3 library (context/input management)
# -s ALLOW_MEMORY_GROWTH=1   # to allow memory resizing -> WARNING: Audio buffers could FAIL!
# -s TOTAL_MEMORY=16777216   # to specify heap memory size (default = 16MB) (67108864 = 64MB)
# -s USE_PTHREADS=1          # multithreading support
# -s WASM=0                  # disable Web Assembly, emitted by default
# -s ASYNCIFY                # lets synchronous C/C++ code interact with asynchronous JS
# -s FORCE_FILESYSTEM=1      # force filesystem to load/save files data
# -s ASSERTIONS=1            # enable runtime checks for common memory allocation errors (-O1 and above turn it off)
# --profiling                # include information for code profiling
# --memory-init-file 0       # to avoid an external memory initialization code file (.mem)
# --preload-file resources   # specify a resources folder for data compilation
# --source-map-base          # allow debugging in browser with source map
LDFLAGS += -s USE_GLFW=3 -s TOTAL_MEMORY=$(BUILD_WEB_HEAP_SIZE) -s FORCE_FILESYSTEM=1

# Build using asyncify
ifeq ($(BUILD_WEB_ASYNCIFY),TRUE)
		LDFLAGS += -s ASYNCIFY
endif

# Add resources building if required
ifeq ($(BUILD_WEB_RESOURCES),TRUE)
		LDFLAGS += --preload-file $(BUILD_WEB_RESOURCES_PATH)
endif

# Add debug mode flags if required
ifeq ($(BUILD_MODE),DEBUG)
		LDFLAGS += -s ASSERTIONS=1 --profiling
endif

# Define a custom shell .html and output extension
LDFLAGS += --shell-file $(BUILD_WEB_SHELL)
EXT = .html

# Define libraries required on linking: LDLIBS
# NOTE: To link libraries (lib<name>.so or lib<name>.a), use -l<name>
#------------------------------------------------------------------------------------------------
# Libraries for web (HTML5) compiling
LDLIBS = $(RAYLIB_RELEASE_PATH)/libraylib.a

# Define source code object files required
#------------------------------------------------------------------------------------------------
PROJECT_SOURCE_FILES := $(shell find $(SOURCEDIR) -name '*.c')

# Define all object files from source files
OBJS = $(patsubst %.c, %.o, $(PROJECT_SOURCE_FILES))

# Define processes to execute
#------------------------------------------------------------------------------------------------

# Default target entry
# Project target defined by PROJECT_NAME
all: $(OBJS)
	$(CC) -o $(PROJECT_NAME)$(EXT) $(OBJS) $(CFLAGS) $(INCLUDE_PATHS) $(LDFLAGS) $(LDLIBS) -D$(PLATFORM)

# Compile source files
# NOTE: This pattern will compile every module defined on $(OBJS)
%.o: %.c
	$(CC) -c $< -o $@ $(CFLAGS) $(INCLUDE_PATHS) -D$(PLATFORM)

# Clean everything
clean-all:
	rm -fv *.o $(PROJECT_NAME).data $(PROJECT_NAME).html $(PROJECT_NAME).js $(PROJECT_NAME).wasm

# Clean just object files
clean:
	rm -fv *.o
