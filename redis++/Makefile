# 32-bit Makefile for the redis-plus-plus example on RHEL 8 x86_64.
# Libraries are expected under /opt/redis32 by default.
# Override PREFIX/LIBDIR if your installation uses another location.
CXX      ?= g++
PREFIX   ?= /opt/redis32
INCLUDEDIR ?= $(PREFIX)/include
LIBDIR   ?= $(PREFIX)/lib
TARGET   ?= redis_example
SOURCES  := main.cpp
OBJECTS  := $(SOURCES:.cpp=.o)

CXXFLAGS ?= -m32 -std=c++17 -O2 -Wall -Wextra
CXXFLAGS += -D_GLIBCXX_USE_CXX11_ABI=0 -I$(INCLUDEDIR)
LDFLAGS  += -m32 -L$(LIBDIR) -Wl,-rpath,$(LIBDIR)
LDLIBS   += -lredis++ -lhiredis -pthread

.PHONY: all clean check
all: $(TARGET)

$(TARGET): $(OBJECTS)
	$(CXX) $(LDFLAGS) -o $@ $(OBJECTS) $(LDLIBS)

%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

check:
	file $(TARGET)

clean:
	rm -f $(TARGET) $(OBJECTS)

# If your 32-bit libraries are installed in /opt/redis32/lib64 instead:
#   make LIBDIR=/opt/redis32/lib64
