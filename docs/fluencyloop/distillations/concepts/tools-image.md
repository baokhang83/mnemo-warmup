# Tools image

An archive only fits the exact JDK and JAR files it was made for, so the tool cannot live in its
own image with its own JDK. The tools image is only a carrier: it holds the tool and two small
scripts, and is copied into the application image. Everything then runs there, with the
application's own Java. If that Java is missing or too old, the tool steps aside and the
application starts without a cache.
