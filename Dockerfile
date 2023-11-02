FROM docker.io/emscripten/emsdk:3.1.47

WORKDIR /
RUN git clone --branch 4.5.0 --depth 1 https://github.com/raysan5/raylib.git

WORKDIR /raylib/src
# RUN mkdir /raylib_install && make PLATFORM=PLATFORM_WEB && make install PLATFORM=PLATFORM_WEB DESTDIR=/raylib_install
RUN make PLATFORM=PLATFORM_WEB

# create a minimal jre
WORKDIR /
RUN wget https://download.java.net/java/GA/jdk21.0.1/415e3f918a1f4062a0074a2794853d0d/12/GPL/openjdk-21.0.1_linux-x64_bin.tar.gz
RUN tar xfvz openjdk-21.0.1_linux-x64_bin.tar.gz
RUN ./jdk-21.0.1/bin/jlink --no-header-files --no-man-pages --compress 2 --strip-debug --add-modules java.base,java.sql --output java21

# put the build dirs into a fresh emsdk image
FROM docker.io/emscripten/emsdk:3.1.47
# COPY --from=0 /raylib_install /raylib
COPY --from=0 /raylib /raylib
COPY --from=0 /java21 /java21

# and finally put the app uberjar
WORKDIR /app
COPY web-builder-standalone.jar .

CMD [ "/java21/bin/java", "-Xmx32M", "-jar", "/app/web-builder-standalone.jar" ]
