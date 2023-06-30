#
# Copyright (C) 2015, BMW Car IT GmbH
#
# Author: Sebastian Mattheis <sebastian.mattheis@bmw-carit.de>
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
# writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
#

FROM ubuntu:22.04

MAINTAINER sebastian.mattheis@bmw-carit.de

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=UTC

# Actualizar el sistema y instalar los paquetes necesarios
RUN apt-get update && apt-get install -y \
    wget \
    gnupg2 \
    lsb-release

# Agregar el repositorio oficial de PostgreSQL
RUN wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add - && \
    echo "deb http://apt.postgresql.org/pub/repos/apt/ $(lsb_release -cs)-pgdg main" >> /etc/apt/sources.list.d/pgdg.list

ADD /pgsql/ /opt/pgsql/
RUN apt-get update
RUN apt-get install -y  \
    postgresql \
    postgis  \
    patch  \
    git  \
    ca-certificates-java  \
    openjdk-17-jdk-headless  \
    python3-psycopg2  \
    python3-numpy  \
    python3-gdal
RUN patch /etc/postgresql/15/main/postgresql.conf < /opt/pgsql/postgresql.conf.patch
# RUN patch --ignore-whitespace --binary /etc/postgresql/15/main/pg_hba.conf < /opt/pgsql/pg_hba.conf.patch
RUN echo "export HOME=/root" >> /root/.bashrc
RUN cd /opt/ && git clone https://github.com/openstreetmap/osmosis.git && cd osmosis && git checkout tags/0.48.3 && ./gradlew assemble && echo "export PATH=${PATH}:/opt/osmosis/package/bin" >> /root/.bashrc
CMD service postgresql start && /bin/bash --rcfile /root/.bashrc
