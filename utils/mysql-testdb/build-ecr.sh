#!/bin/bash
#
# Quick Docker build of mysql-testdb
#

ECR=${AWSID}.dkr.ecr.${REGION}.amazonaws.com
PRE=${REPONAME}

IFS=/
set -- $PWD
unset IFS
shift $[$#-2]
if [ $2 == 'mysql-testdb' ]; then
    echo "Do NOT run from mysql-testdb, run from subdirs"
    exit 1
fi

export APP=$1 VER=$2
aws ecr describe-repositories --repository-names $PRE/$APP 2> /dev/null
[ $? -gt 0 ] && \
    aws ecr create-repository --repository-name $PRE/$APP

BUILD_ARGS=
[ ! -z "$MYSQL_PASSWORD" ] && BUILD_ARGS="$BUILD_ARGS --build-arg MYSQL_PASSWORD=$MYSQL_PASSWORD"
[ ! -z "$MYSQL_ROOT_PASSWORD" ] && BUILD_ARGS="$BUILD_ARGS --build-arg MYSQL_PASSWORD=$MYSQL_ROOT_PASSWORD"

export REPO=$ECR/$PRE
docker build -t $APP:$VER --network host $BUILD_ARGS -f Dockerfile .
docker tag $APP:$VER $REPO/$APP:$VER

docker push $REPO/$APP:$VER
if [ $? -gt 0 ]; then
    echo -e "Auto Docker push failed, try manually using:\ndocker push $REPO/$APP:$VER"
fi
