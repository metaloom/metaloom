FROM adoptopenjdk/openjdk11:x86_64-alpine-jre-11.0.5_10

ENV LOOM_AUTH_KEYSTORE_PATH=/keystore/keystore.jks
ENV LOOM_GRAPH_BACKUP_DIRECTORY=/backups
ENV LOOM_GRAPH_DB_DIRECTORY=/graphdb
ENV LOOM_PLUGIN_DIR=/plugins
ENV LOOM_BINARY_DIR=/uploads
ENV LOOM_TEMP_DIR=/tmp
ENV HOME=/look
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx512m"

EXPOSE 8080
EXPOSE 8443
EXPOSE 8081

USER root
RUN adduser -D -u 1000 -G root -h /loom loom && \
    mkdir /uploads   && chown 1000:0 /uploads  -R && chmod 770 /uploads  && \
    mkdir /plugins   && chown 1000:0 /plugins  -R && chmod 770 /plugins  && \
    mkdir /keystore  && chown 1000:0 /keystore -R && chmod 770 /keystore && \
    mkdir /config    && chown 1000:0 /config   -R && chmod 770 /config && ln -s /config /loom/config && \
    mkdir /loom/data && \
    chown 1000:0 /loom -R && \
    chmod 770 /loom -R
ADD --chown=1000:0 ./target/loom-server*jar /loom/loom.jar

USER loom
WORKDIR /loom
VOLUME /uploads
VOLUME /plugins
VOLUME /keystore
VOLUME /config

CMD [ "java", "-Djna.tmpdir=/tmp/.jna", "-Duser.dir=/loom", "-jar" , "loom.jar"]
