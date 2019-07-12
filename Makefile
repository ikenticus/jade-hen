# Makefile for jade-hen covering all the CLI commands in README.md

REGION := us-east-5
AWS_ID := 123456789098
VPC_ID := vpc-1a2b3c

EKS := test
NODETYPE := m5.large

install:
	@echo Usage: make -s \<target\>

iam:
	aws iam create-group --group-name jenkins
	aws iam create-user --user-name jenkins
	aws iam add-user-to-group --user-name jenkins --group-name jenkins
	aws iam create-access-key --user-name jenkins | tee jenkins-iam.json
	ssh-keygen -t rsa -f jenkins-ssh -C jenkins
	aws iam upload-ssh-public-key --user-name=jenkins --ssh-public-key-body file://jenkins-ssh.pub

codecommit:
	aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AWSCodeCommitReadOnly --group-name jenkins
	#aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AWSCodeCommitPowerUser --group-name jenkins
	#aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AWSCodeCommitFullAccess --group-name jenkins
	@echo Generate username/password pair here:
	@echo https://console.aws.amazon.com/iam/home#/users/jenkins?section=security_credentials

sqs:
	aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AmazonSQSReadOnlyAccess --group-name jenkins
	aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole --group-name jenkins
	aws sqs create-queue --queue-name jenkins-codecommit-updates

sns:
	aws sns create-topic --name trigger-jenkins
	aws sns subscribe --topic-arn arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins --protocol sqs --notification-endpoint arn:aws:sqs:${REGION}:${AWS_ID}:jenkins-codecommit-updates
	aws sns list-subscriptions-by-topic --topic-arn arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins

trigger:
	@if [ ! -z "$REPO" ]; then \
	    aws codecommit put-repository-triggers --repository-name ${REPO} --triggers \
	        events=all,destinationArn=arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins,branches=[],name="Deploy ${REPO}"; \
	fi

eks-keypair:
	aws ec2 create-key-pair --key-name eks-worker-keypair --region ${REGION}

eks-init-abc-az:
	eksctl create cluster --name=test --region ${REGION} \
	    --asg-access --external-dns-access --full-ecr-access \
	    --node-type=${NODETYPE} --nodes=3 \
	    --nodes-min=3 --nodes-max=10 --zones=${AZ} \
	    --ssh-access --ssh-public-key=eks-worker-keypair

eks-init-all-azs:
	AZ=$(aws ec2 describe-availability-zones --region ${REGION} \
	    --query AvailabilityZones[].ZoneName | jq -r 'join(",")')
	eksctl create cluster --name=test --region ${REGION} \
	    --asg-access --external-dns-access --full-ecr-access \
	    --node-type=${NODETYPE} --nodes=3 \
	    --nodes-min=3 --nodes-max=10 \
		--zones=${REGION}a,${REGION}b,${REGION}c \
	    --ssh-access --ssh-public-key=eks-worker-keypair

eks-init-all-priv:
	PRIV=$(aws ec2 describe-subnets \
		--filter Name=vpc-id,Values=${VPC_ID} | \
		jq -r '.Subnets | map(select(.Tags[0].Value | startswith("private")) | .SubnetId) | join(",")')
	eksctl create cluster --name=test --region ${REGION} \
	    --asg-access --external-dns-access --full-ecr-access \
	    --node-type=${NODETYPE} --nodes=3 \
	    --nodes-min=3 --nodes-max=10 \
		--vpc-private-subnets ${PRIV} \
	    --ssh-access --ssh-public-key=eks-worker-keypair

eks-init-all-pub:
	PUB=$(aws ec2 describe-subnets \
		--filter Name=vpc-id,Values=${VPC_ID} | \
		jq -r '.Subnets | map(select(.Tags[0].Value | contains("public")) | .SubnetId) | join(",")')
	eksctl create cluster --name=test --region ${REGION} \
	    --asg-access --external-dns-access --full-ecr-access \
	    --node-type=${NODETYPE} --nodes=3 \
	    --nodes-min=3 --nodes-max=10 \
		--vpc-public-subnets ${PUB} \
	    --ssh-access --ssh-public-key=eks-worker-keypair

eks-kube:
	eksctl utils write-kubeconfig --name ${EKS} --region ${REGION}

nodegroup-add:
	eksctl create nodegroup --cluster ${EKS} --region ${REGION} \
	    --asg-access --node-ami=auto --nodes=3 --nodes-min=3 --nodes-max=10 \
	    --ssh-access --ssh-public-key=eks-worker-keypair

nodegroup-list:
	eksctl get nodegroup --cluster ${EKS} --region ${REGION}

nodegroup-drop:
	@if [ ! -z "${NODENAME}"]; then \
	    eksctl delete nodegroup --cluster ${EKS} --region ${REGION} --name=${NODENAME}
	fi

ext-dns:
	aws iam list-instance-profiles --region ${REGION} | grep Name\":.*eksctl
	@if [ ! -z "${ROLENAME}"]; then \
	    aws iam attach-role-policy --role-name ${ROLENAME} --policy-arn arn:aws:iam::{$AWS_ID}:policy/AmazonRoute53ChangeAccess; \
	fi
	aws route53 create-hosted-zone --name "test.domain.net." --caller-reference "test-domain-$(date +%s)"
	kubectl create -f init/kube/external-dns.yaml
	kubectl create -f init/kube/jenkins-ingress.yaml

kube-dashboard:
	kubectl create -f https://raw.githubusercontent.com/kubernetes/dashboard/master/aio/deploy/recommended/kubernetes-dashboard.yaml

kube-metrics-server:
	DOWNLOAD_URL=$(curl --silent "https://api.github.com/repos/kubernetes-incubator/metrics-server/releases/latest" | jq -r .tarball_url)
	DOWNLOAD_VERSION=$(grep -o '[^/v]*$' <<< $DOWNLOAD_URL)
	curl -Ls $DOWNLOAD_URL -o metrics-server-$DOWNLOAD_VERSION.tar.gz
	mkdir metrics-server-$DOWNLOAD_VERSION
	tar -xzf metrics-server-$DOWNLOAD_VERSION.tar.gz --directory metrics-server-$DOWNLOAD_VERSION --strip-components 1
	kubectl apply -f metrics-server-$DOWNLOAD_VERSION/deploy/1.8+/
	kubectl get deployment metrics-server -n kube-system

kube-namespace:
	kubectl create -f init/kube/namespace.json
	kubectl config get-contexts
	@if [ ! -z "${CONTEXT}"]; then \
	    kubectl config use-context ${CONTEXT}; \
	fi

kube-tiller:
	kubectl apply -f init/kube/tiller.yaml

kube-secret:
	@if [ ! -z "${NAMESPACE}"]; then \
	    kubectl apply -f init/kube/domain-secret-staging.yaml -n ${NAMESPACE}; \
	else \
		kubectl apply -f init/kube/domain-secret-staging.yaml; \
	fi

nginx-ingress:
	helm install stable/nginx-ingress --name nginx-ingress \
	    --set controller.stats.enabled=true \
	    --set controller.metrics.enabled=true \
	    --set controller.publishService.enabled=true

jenkins:
	helm install stable/jenkins --name jenkins --values init/helm/jenkins-values.yaml

logs-cluster:
	aws logs describe-log-groups --region ${REGION}
	aws eks describe-cluster --name test --region ${REGION} | jq -r '.cluster.logging.clusterLogging'
	aws eks update-cluster-config --name test --region ${REGION} \
	    --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'
	aws logs put-retention-policy --region ${REGION} --log-group-name /aws/eks/test/cluster --retention-in-days 3

logs-console:
	aws iam create-policy --policy-name k8s-logs --policy-document file://init/aws/k8s-logs.json
	helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
	helm search fluentd-cloudwatch
	helm install --name fluentd incubator/fluentd-cloudwatch \
	 --set awsRole=arn:aws:iam::${AWS_ID}:role/k8s-logs,awsRegion=${REGION},rbac.create=true
	aws logs put-retention-policy --region ${REGION} --log-group-name kubernetes --retention-in-days 7

test:
	echo Test
