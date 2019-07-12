# jade-hen
Jenkins AWS Docker EKS: Helm external-dns nginx-ingress


## Abstract
Jenkins continuous integration using AWS and related services inclusively.
EKS implies Kubernetes whereas AWS implies Route53 and CodeCommit (and not GitHub, GitLab, BitBucket, etc).
Jenkins CI replaces the CodePipeline and CodeBuild services but everything else taps into Amazon Web Services.
Scattered across the internet were examples and tutorials utilizing certain aspects of `jade-hen` but after spending weeks trying to piece them altogether,
it seemed prudent to create this repository in order to assemble all the disparate components in one place to help others save time.


## Ingredients
The acronym of this project may list the vital components, but not in order.
Assembling the deployment recipe utilizes the following ingredients,
but many of the ingredients may be reused in between the others:
* [IAM](#iam) Credentials
* [CodeCommit](#codecommit) SCM
* [EKS](#eks) Kubernetes Cluster
* [kubectl](#kubectl) (kubernetes control tool)
* [nginx-ingress](#nginx) replacing Elastic Load Balancers
* [external-dNS](#extdns) using Route53
* [Jenkins](#jenkins) CI with Pods
* [Docker](#docker) Containers
* [Helm](#helm) Charts
* [Logs](#logs) for Cluster and Pods


## Procedure
Obviously, even with all the ingredients interacting recursively,
we need to have some linearly ordered procedure to get started,
so let us lay out the roadmap as formally as we can here.
As a prerequisite, your local environment should have the necessary tools to install the ingredients.
From a MacBook, this is usually accomplished via `homebrew` and the following commands should set up  your local environment:
```
brew tap weaveworks/tap
brew cask install docker
brew install awscli docker-machine jq kubectl kubernetes-helm weaveworks/tap/eksctl
```


### <a id="iam"></a> IAM

Most of the AWS IAM credentials can be set up using the CLI, but you are more than welcome to attempt to configure them via the web interface as well.  For Jenkins, this will require a user/group that has access to the AWS components that will be required to build and deploy to EKS.
```
aws iam create-group --group-name jenkins
aws iam create-user --user-name jenkins
aws iam add-user-to-group --user-name jenkins --group-name jenkins
aws iam create-access-key --user-name jenkins
```
If you are using SSH keys, generate one and upload the public key:
```
ssh-keygen -t rsa -f jenkins-ssh -C jenkins
aws iam upload-ssh-public-key --user-name=jenkins --ssh-public-key-body file://jenkins-ssh.pub
```

You can search for the permissions that you might require:
```
aws iam list-policies | grep Arn | egrep "SQS|CodeCommit"
```
But at a minimum, for CodeCommit builds in `jade-hen` the following policies must be attached to the `jenkins` account:
```
aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AWSCodeCommitReadOnly --group-name jenkins
```
Utilize `AWSCodeCommitPowerUser` or `AWSCodeCommitFullAccess` in place of `AWSCodeCommitReadOnly` as necessary.

NOTE: unable to get `aws-codecommit-trigger` working with Multibranch Pipelines, so the rest of this `IAM` section is informational only, as the current `jade-hen` project does not utilize `SQS`, `SNS` or `CodeCommit` triggers (yet).

For `SQS` related triggers, the following policies should be added as well:
```
aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/AmazonSQSReadOnlyAccess --group-name jenkins
aws iam attach-group-policy --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole --group-name jenkins
```



### <a id="codecommit"></a> CodeCommit

Since HTTPS Git credentials for AWS CodeCommit cannot be generate from command line, go to `https://console.aws.amazon.com/iam/home#/users/jenkins?section=security_credentials` and Generate a username/password pair.

Create a new pipeline repository in your AWS CodeCommit and copy/commit the `/vars` directory from `jade-hen` into this new pipeline repository. This will allow you to make any changes to `jade-hen` as necessary, such as modifying the `common.groovy` options.

NOTE: unable to get `aws-codecommit-trigger` working with Multibranch Pipelines, so the rest of this `CodeCommit` section is informational only, as the current `jade-hen` project does not utilize `SQS`, `SNS` or `CodeCommit` triggers (yet)

In order to utilize the `aws-codecommit-trigger` plugin in Jenkins, we need to create the SQS queue and SNS topic:
```
aws sqs create-queue --queue-name jenkins-codecommit-updates
aws sns create-topic --name trigger-jenkins
aws sns subscribe --topic-arn arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins --protocol sqs --notification-endpoint arn:aws:sqs:${REGION}:${AWS_ID}:jenkins-codecommit-updates
```
The following command to grant everybody permissions via CLI does not work (as of this writing):
```
aws sqs add-permission --queue-url https://${REGION}.queue.amazonaws.com/${AWS_ID}/jenkins-codecommit-updates --aws-account-ids "*" --actions SendMessage
```
so add it via web interface `https://${REGION}.console.aws.amazon.com/sqs/home`, select `jenkins-codecommit-updates`, click on `Permissions` tab, and `Add a Permission` according to the CLI value above. For security, `Add Condition` and limit it the SourceARN for that SNS Topic: `arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins`

You can check your subscription:
```
aws sns list-subscriptions-by-topic --topic-arn arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins
```
and then add the CodeCommit trigger for each repository:
```
aws codecommit put-repository-triggers --repository-name ${REPO_NAME} --triggers events=all,destinationArn=arn:aws:sns:${REGION}:${AWS_ID}:trigger-jenkins,branches=[],name="Deploy ${REPO_NAME}"
```


### <a id="eks"></a> EKS

To use the AWS CLI with Amazon EKS, you must have at least version 1.16.73 of the AWS CLI installed
```
aws --version
```

Before creating a Kubernetes Cluster, it would be best to create a keypair for the instances in the cluster:
```
aws ec2 create-key-pair --key-name eks-worker-keypair --region $REGION
```

After creating the keypair, it is fairly easy to spin up a cluster with `eksctl` across all availability zones:
```
AZ=$(aws ec2 describe-availability-zones --region $REGION \
    --query AvailabilityZones[].ZoneName | jq -r 'join(",")')

eksctl create cluster --name=test --region $REGION \
    --asg-access --external-dns-access --full-ecr-access \
    --node-type=$NODETYPE --nodes=3 \
    --nodes-min=3 --nodes-max=10 --zones=$AZ \
    --ssh-access --ssh-public-key=eks-worker-keypair
```
Alternatively, you can specify specific AZs instead of applying them all:
```
    --zones=${REGION}a,${REGION}b,${REGION}c
```
Or even replace `--zones` with either private or public subnets (or both)
```
PRIV=$(aws ec2 describe-subnets \
    --filter Name=vpc-id,Values=$VPCID | \
    jq -r '.Subnets | map(select(.Tags[0].Value | startswith("private")) | .SubnetId) | join(",")')
```
```
PUB=$(aws ec2 describe-subnets \
    --filter Name=vpc-id,Values=$VPCID | \
    jq -r '.Subnets | map(select(.Tags[0].Value | contains("public")) | .SubnetId) | join(",")')
```
```
    --vpc-private-subnets $PRIV --vpc-public-subnets $PUB
```

If you  need larger node-types or you want to replace the AMI for the nodegroup, you need to first create a new one.
```
eksctl create nodegroup --cluster test --region $REGION \
    --node-ami=auto --nodes=3 --node-type=$NODETYPE \
    --ssh-access --ssh-public-key=eks-worker-keypair
```
Then display all your nodegroups to pinpoint the names of the nodegroups that you are keeping versus the ones you are discarding.

```
eksctl get nodegroup --cluster test --region $REGION
```
You can initiate a deletion on the old nodegroup:
```
eksctl delete nodegroup --cluster test --region $REGION --name=$NODENAME
```
but the Kubernetes cluster will smartly drain the old nodegroup as it builds the replacement pods in the new nodegroup, after which the old nodegroup will be automatically terminated.

Based on what you need this Kubernetes Cluster to manage, you will need to attach policies as needed. First you must obtain the instance profile:
```
aws iam list-instance-profiles | grep Name\":.*eksctl
```
which should return something like this:
```
           "InstanceProfileName": "eksctl-test-nodegroup-node36-NodeInstanceProfile-50M3R6NDH65H",
                   "RoleName": "eksctl-test-nodegroup-node36-NodeInstanceRole-TR1PLETR1D",
```
Then attach the policies as needed:
```
aws iam attach-role-policy --role-name eksctl-test-nodegroup-node36-NodeInstanceRole-TR1PLETR1D --policy-arn arn:aws:iam::${AWS_ID}:policy/AmazonRoute53ChangeAccess
```


### <a id="kubectl"></a> kubectl

Using `kubectl` you can install the various useful Kubernetes tools, like the dashboard:
```
kubectl create -f https://raw.githubusercontent.com/kubernetes/dashboard/master/aio/deploy/recommended/kubernetes-dashboard.yaml
```

Now that heapster was deprecated as of 1.13, install metrics-server:
```
DOWNLOAD_URL=$(curl --silent "https://api.github.com/repos/kubernetes-incubator/metrics-server/releases/latest" | jq -r .tarball_url)
DOWNLOAD_VERSION=$(grep -o '[^/v]*$' <<< $DOWNLOAD_URL)
curl -Ls $DOWNLOAD_URL -o metrics-server-$DOWNLOAD_VERSION.tar.gz
mkdir metrics-server-$DOWNLOAD_VERSION
tar -xzf metrics-server-$DOWNLOAD_VERSION.tar.gz --directory metrics-server-$DOWNLOAD_VERSION --strip-components 1
kubectl apply -f metrics-server-$DOWNLOAD_VERSION/deploy/1.8+/
kubectl get deployment metrics-server -n kube-system
```

In order to create a specific namespace on the cluster, you should edit the `namespace.json` file and create using:
```
kubectl create -f init/kube/namespace.json
kubectl config get-contexts
kubectl config use-context test
```
You can decide how many namespaces you will want, test vs live, or test vs stage vs production, etc,
depending on your level of organization.

There are commands to adjust (i.e. shorten) the contexts in `kubectl`
```
kubectl config rename-context arn:aws:eks:us-east-1:${AWS_ID}:cluster/test test-va
kubectl config rename-context arn:aws:eks:us-east-2:${AWS_ID}:cluster/test test-oh
```
Or you can modify `~/.kube/config` directly, if you know what you are doing.

Luckily, if you accidentally foobar the `.kube/config`, you can regenerate it using:
```
eksctl utils write-kubeconfig --name test --region $REGION
```

Finally, in order to deploy the rest of the systems via `helm` we need to install `tiller` (which we will migrate away from in helm3):
```
kubectl apply -f init/kube/tiller.yaml
helm init --service-account tiller --upgrade
```

You might also take this opportunity to add any kubernetes secrets you might need for helm deployment:
```
kubectl apply -f init/kube/domain-secret-staging.yaml -n test
```
Most likely you will need corresponding secrets in each of your namespaces.


### <a id="nginx"></a> nginx-ingress

To install `nginx-ingress`, you basically invoke the stable helm chart:
```
helm install stable/nginx-ingress --name nginx-ingress \
    --set controller.stats.enabled=true \
    --set controller.metrics.enabled=true \
    --set controller.publishService.enabled=true
```

If you want nginx-ingress to handle TLS using AWS Certificate Manager, obtain the certificate ARN:
```
aws acm list-certificates | jq -r '.CertificateSummaryList[] | select(.DomainName | endswith("domain.net")) | .CertificateArn'
```
update the `init/helm/nginx-values.yaml`, then install using helm chart values override:
```
helm install stable/nginx-ingress --name nginx-ingress \
    --values init/helm/nginx-values.yaml
```

### <a id="extdns"></a> external-dns

In order to use `external-dns`, you should decide whether or not you want to control an existing or a subdomain (safer). In order to create a new subdomain, you can just run:
```
aws route53 create-hosted-zone --name "test.domain.net." --caller-reference "test-domain-$(date +%s)"
```

Copy the displayed `/hostedzone/ROUTE53_ZONEID` into the `external-dns.yaml` file and install it via `kubectl`:
```
kubectl create -f init/kube/external-dns.yaml
```

After running helm deployments, you can always add additional DNS names to `external-dns` as needed. For example, the `helm install` in the next section will automatically create an `ingress` for `jenkins.$REGION.test.domain.net` but you want users to utilize the friendlier name `jenkins.domain.net` via their browser and also for Jenkins internal configurations. Modify the following YAML template and install:
```
kubectl create -f init/kube/jenkins-ingress.yaml
```


### <a id="jenkins"></a> Jenkins

Now that external-dns and nginx-ingress are installed properly, the Jenkins CI can be simple installed using helm as well: `helm install stable/jenkins --name jenkins --values init/helm/jenkins-values.yaml`

After the DNS kicks in, you can log into the Jenkins on your browser as admin using the auto-generated password: `kubectl get secret --namespace test jenkins -o jsonpath="{.data.jenkins-admin-password}" | base64 --decode | pbcopy`

First thing is the make sure that all the Jenkins URL references are updated. `Manage Jenkins` > `Configure System` and update all Jenkins URLs appropriately to use either the FQDN of the `jenkins.region.test.domain.net` or a CNAME `jenkins.domain.com` --- it is important that all Jenkins URLs are correct before you modify the Global Security because you will not be able to login afterwards should the Jenkins URL be inaccessible.

Then, you can add alternate login methods like Google OAuth. `Manage Jenkins` > `Configure Global Security` and modify access control (i.e. for Login with Google, obtain the client id/secret from `console.developers.google.com` and add valid mail domains to the Google Apps Domain box, after you add `http://jenkins.region.test.domain.net/` to the Authorized JavaScript origins
 and `http://jenkins.region.test.domain.net/securityRealm/finishLogin` to the Authorized redirect URIs)

 From the CodeCommit `jenkins` user account, the following credentials will need to be created from `Credentials` > `Jenkins` > `Global credentials` > `Add Credentials`:
 * `Secret text` storing the AWS_ID account number as the secret with the ID `aws-id` for the sample `Jenkinsfile` below.
 * `Secret text` storing the Slack token as the secret with the ID `slack-token` for slack notifications
 * `Username with password` using HTTPS user/pass pair with ID `jenkins-https` and Description `Jenkins user/pass for SCM`.
 * `AWS Credentials` using the IAM access/secret with ID `jenkins-iam` and Description `Jenkins access/secret for ECR`.
 * `SSH Username with private key` if you generated an SSH key earlier, using ID `jenkins-ssh` and username `jenkins` and pasting the Private Key and Passphrase utilized during the [IAM](#iam) process.

If you plan on using the slack notification plugin: `Manage Jenkins` > `Configure System` > `Global Slack Notifier Settings` and supply the `Team Subdomain`, `Integration Token Credential ID`, and `Channel or Slack ID`

Finally, add the newly created `jade-hen` pipeline above as a Global Pipeline Library: `Manage Jenkins` > `Configure System` > `Global Pipeline Libraries`, name it something like `shared-pipeline` and set up the SCM using the `jenkins-https` credentials.

Each repository will have its own `ci/Jenkinsfile` calling the shared Pipeline in order to handle the deployment steps. Make sure to update the region and helm versions inside the `common.groovy` to match your AWS/EKS configuration.

For your repository, `New Item` > `Multibranch Pipeline` to create pipeline (i.e. `build-app-sample`):
* Under `Branch Sources` > `Add source` and select `Git` then paste the CodeCommit URL into the Project Repository and select the `jenkins-https` Credentials from before. Also ensure that `Behaviors` contains: Discover branches, tags, ...
* Under `Build Configuration` change Script Path to `ci/Jenkinsfile.build` or whatever you may have named the build `Jenkinsfile`
* Under `Scan Multibranch Pipeline Triggers` check the `Periodically if not otherwise run` and set the `Interval` to something as frequent needed.

For deployment, `New Item` > `Pipeline` to create pipeline (i.e. `deploy-app-sample`):
* Under `Pipeline` > `Pipeline script from SCM` and select `Git` then paste the CodeCommit URL, same as the Multibranch source
* Under `Script path`, change that to `ci/Jenkinsfile.deploy` or whatever you may have named the deployment `Jenkinsfile`

Once you have your initial Jenkins configured, `Manage Jenkins` > `ThinBackup` > `Settings` and set the Backup directory to something like `/var/jenkins/backup`, check `Move old backups to ZIP files` and `Save`. Click `Backup Now` and when completed, you should see the latest backup listed under `Restore`. Afterwards you can copy the backup over using `kubectl`, either by connecting to the pod OR copying the files from the pod to your local machine:
```
PODNAME=$(kubectl get pods -l app.kubernetes.io/name=jenkins -o name | cut -d\/ -f2)
kubectl exec -it $PODNAME /bin/bash ### OR ###
kubectl exec -it $PODNAME tar zcvf /tmp/backup.tgz /var/jenkins/backup/
kubectl cp $PODNAME:tmp/backup.tgz /tmp/
```


### <a id="docker"></a> Docker

Each repository will have its own `ci/Dockerfile` to custom build its container for ECR. The sample `Dockerfile`.builds are meant as a guide for some code build variations and not a complete list of all available coding languages/frameworks in existence at the time of this writing. You will have to learn to write your own `Dockerfile` using these examples and whatever you can find but, from my experience, the only tips gathered thus far are:
* Instead of logging to `/var/log/${APP}`, all logging should go to `/dev/stdout` so that `kubectl log ${POD}` will function correctly
* For Golang, since it can compile binaries, you can start with whatever OS you like to debug, but eventually should evolve into a minimal base like `alpine` or as basic an OS as can be found or built
* For `node.js`, while `pm2` was undoubtedly a great resource manager, it seems redundant to execute it within a kubernetes pod since the `deployment` and `replica set` handle that using customized resource limits, so better to just end the `Dockerfile` with `CMD ["node", "index.js"]`

Any docker `--build-arg` arguments can be passed to the `appBuild` as `buildArgs: [Map]` or as a `dockerBuildArgs` closure (see `ci/Jenkinsfile.build` for examples of both)

### <a id="helm"></a> Helm

Each repository will have its own `ci/helm` charts to deploy to test/live environments. Existing helm charts are used above to deploy standardized versions of many of the apps in this project, so either clone of the various examples above or create your own with `helm create`. Obviously, `deployment` and `replica set` are taken care of almost automatically. Add `service` only if you plan on utilizing you limited supply of Elastic IP or Elastic Load Balancing quota, but the point of this project is to utilize the `nginx-ingress` to avoid all of that and `external-dns` to automatically update `Route53` during deployment so do not forget to include the `ingress` component.

Like the docker `buildArgs` Map, overrides to helm can be passed to `appDeploy` via `helmArgs` or by creating a `ci/helm/example/overrides/branch-name.yaml` within the branch itself.


### <a id="logs"></a> Logs

For the cluster logs, just enable the appropriate fields in the CloudWatch:
```
aws logs describe-log-groups --region $REGION
aws eks describe-cluster --name test --region $REGION | jq -r '.cluster.logging.clusterLogging'
aws eks update-cluster-config --name test --region $REGION \
    --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'
```

For the console logs from the pods, create the policy and deploy `fluentd-cloudwatch` from the Google incubator:
```
aws iam create-policy --policy-name k8s-logs --policy-document file://init/aws/k8s-logs.json
helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
helm search fluentd-cloudwatch
helm install --name fluentd incubator/fluentd-cloudwatch \
 --set awsRole=arn:aws:iam::${AWS_ID}:role/k8s-logs,awsRegion=${REGION},rbac.create=true
```

To avoid CloudWatch growing uncontrollably, make sure to set the retentions as well:
```
$ aws logs put-retention-policy --region $REGION --log-group-name kubernetes --retention-in-days 7
$ aws logs put-retention-policy --region $REGION --log-group-name /aws/eks/test/cluster --retention-in-days 3
```
