FROM openjdk:8-alpine
MAINTAINER Rubens Minoru Andako Bueno <rubensmabueno@hotmail.com>

RUN set -ex && \
    apk upgrade --no-cache && \
    apk add --no-cache curl python snappy

# Installing Presto
WORKDIR /tmp

ENV PRESTO_VERSION 0.216
ENV PRESTO_HOME /opt/presto
ENV PRESTO_ETC_DIR /etc/presto
ENV PRESTO_DATA_DIR /data

COPY ./presto-server/target/presto-server-0.221-SNAPSHOT.tar.gz /tmp
COPY ./presto-cli/target/presto-cli-0.221-SNAPSHOT-executable.jar /usr/local/bin/presto

RUN ls -al
RUN pwd

RUN mkdir -p ${PRESTO_HOME} && \
    mkdir -p ${PRESTO_ETC_DIR}/catalog && \
    tar --strip 1 -C ${PRESTO_HOME} -xf ./presto-server-0.221-SNAPSHOT.tar.gz && \
    chmod +x /usr/local/bin/presto && \
    mkdir ${PRESTO_HOME}/scripts

WORKDIR ${PRESTO_HOME}

ENV JAVA_OPTS "-server"

CMD ./bin/launcher --config=${PRESTO_ETC_DIR}/config.properties --log-levels-file=${PRESTO_ETC_DIR}/log.properties --node-config=${PRESTO_ETC_DIR}/node.properties run
