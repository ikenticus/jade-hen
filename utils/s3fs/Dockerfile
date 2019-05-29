FROM ubuntu:18.10

ENV DEBIAN_FRONTEND=noninteractive
ENV AWS_KEY ''
ENV AWS_SECRET_KEY ''
ENV MNT_POINT /data
ENV S3_BUCKET ''
ENV S3_REGION ''

RUN apt-get -y update
RUN apt-get -y install automake autotools-dev g++ git libcurl4-gnutls-dev libfuse-dev libssl-dev libxml2-dev make pkg-config
RUN git clone https://github.com/s3fs-fuse/s3fs-fuse.git && \
    cd s3fs-fuse && \
    ./autogen.sh && \
    ./configure && \
    make && \
    make install && \
    ldconfig

RUN apt-get purge -y wget automake autotools-dev g++ git
RUN apt-get -y autoremove --purge 
RUN rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

CMD echo "${AWS_KEY}:${AWS_SECRET_KEY}" > /etc/passwd-s3fs && \
    chmod 0400 /etc/passwd-s3fs && \
    mkdir -p "$MNT_POINT" && \
    /usr/local/bin/s3fs $S3_BUCKET $MNT_POINT -f \
    -o endpoint=${S3_REGION},allow_other,use_cache=/tmp,max_stat_cache_size=1000,stat_cache_expire=900,retries=5,connect_timeout=10

