FROM alpine:3.23.4 as build

WORKDIR /home/app

COPY src /home/app/
COPY pom.xml /home/app/
COPY settings.xml /home/app/

RUN apk add --update wget zip openjdk21-jdk

ARG MAVEN_VERSION=3.9.15

RUN wget -q -O /tmp/maven.zip https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.zip \
 && unzip /tmp/maven.zip -d /tmp/ \
 && mkdir -p /usr/local/maven/ \
 && mv /tmp/apache-maven-${MAVEN_VERSION}/* /usr/local/maven/ \
 && rm -Rf /tmp/apache-maven-${MAVEN_VERSION}/ \
 && /usr/local/maven/bin/mvn -s /home/app/settings.xml -f /home/app/pom.xml package -DskipTests \
 && rm -fr ~/.m2/repository

FROM eclipse-temurin:21-ubi10-minimal

ENV TZ=BRT+3

LABEL\
 org.opencontainers.image.title="Proxy API"\
 org.opencontainers.image.description="Proxy HTTP/HTTPS para APIs REST"\
 org.opencontainers.image.authors="Claudiney Nascimento"\
 org.opencontainers.image.source="https://github.com/claudineyns/quarkus-camel-proxy.git"\
 org.opencontainers.image.version="0.0.1"\
 org.opencontainers.image.vendor="Private"\
 org.opencontainers.image.licenses="MIT"\
 io.openshift.expose-services="8181:http,8182:https"\
 io.openshift.tags="java,backend"\
 io.openshift.display-name="Proxy API"\
 io.k8s.description="Proxy HTTP/HTTPS para APIs REST"\
 io.k8s.display-name="Proxy API"

COPY --from=build /home/app/target/*-runner.jar /deployments/app.jar

USER 1001

EXPOSE 8181 8282

CMD ["java","-jar","/deployments/app.jar"]
