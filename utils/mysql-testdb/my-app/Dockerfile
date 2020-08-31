FROM mysql:5.7

ARG MYSQL_DATABASE=drupal8
ARG MYSQL_HOST=localhost
ARG MYSQL_PASSWORD=drupal
ARG MYSQL_ROOT_PASSWORD=drupal
ARG MYSQL_USER=drupal

# mysql Docker hub images already created:
# RUN mkdir /docker-entrypoint-initdb.d

COPY drupal.sql.gz /docker-entrypoint-initdb.d/
