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
* [EKS](#eks) Kubernetes Cluster
* [kubectl](#kubectl) Namespaces
* [Nginx-Ingress](#nginx) replacing Elastic Load Balancers
* [External-DNS](#extdns) using Route53
* [Jenkins](#jenkins) CI with Pods
* [Docker](#docker) Containers
* [Helm](#helm) Charts


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


### <a id="eks"></a> EKS Kubernetes Cluster

Before creating a Kubernetes Cluster, it would be best to create a keypair for the instances in the cluster:
```
aws ec2 create-key-pair --key-name devops-keypair --region $REGION
```

After creating the keypair, it is fairly easy to spin up a cluster with `eksctl`:
```
eksctl create cluster --name=test --region $REGION \
    --asg-access --nodes=3 --node-type=$NODETYPE \
    --zones=us-east-2a,us-east-2b,us-east-2c \
    --external-dns-access \
    --ssh-access --ssh-public-key=eks-worker-keypair
```
If you  need larger node-types or you want to replace the AMI for the nodegroup, you need to create a new one.
```
eksctl create nodegroup --cluster test --region $REGION \
    --asg-access --node-ami=auto --nodes=3 --nodes-min=3 --nodes-max=10 \
    --ssh-access --ssh-public-key=eks-worker-keypair
```
Then display all your nodegroups to pinpoint the names of the nodegroups that you are keeping versus the ones you are discarding.

```
eksctl get nodegroup --cluster test --region $REGION
```
You can initiate a deletion on the old nodegroup:
```
eksctl delete nodegroup --cluster=test --region $REGION --name=$NODENAME
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
aws iam attach-role-policy --role-name eksctl-test-nodegroup-node36-NodeInstanceRole-TR1PLETR1D --policy-arn arn:aws:iam::AMAZONACCTID:policy/AmazonRoute53ChangeAccess
```


### <a id="kubectl"></a> kube-ctl

Using `kubectl` you can install the various useful Kubernetes tools:
```
kubectl apply --filename https://raw.githubusercontent.com/kubernetes/heapster/master/deploy/kube-config/rbac/heapster-rbac.yaml
kubectl apply --filename https://raw.githubusercontent.com/kubernetes/heapster/master/deploy/kube-config/standalone/heapster-controller.yaml
kubectl create -f https://raw.githubusercontent.com/kubernetes/dashboard/master/aio/deploy/recommended/kubernetes-dashboard.yaml
```

In order to create a specific namespace on the cluster, you should edit the `namespace.json` file and create using:
```
kubectl create -f namespace.json
```

There are commands to adjust the contexts in `kubectl` or you can modify `~/.kube/config` directly, if you know what you are doing
```
kubectl config get-contexts
kubectl config use-context test
```

Luckily, if you accidentally foobar the `.kube/config`, you can regenerate it using:
```
eksctl utils write-kubeconfig --name test --region $REGION
```

Finally, in order to deploy the rest of the systems via `helm` we need to install `tiller` (which we will migrated away from in helm3):
```
kubectl apply -f tiller.yaml
```


### <a id="nginx"></a> nginx-ingress

To install `nginx-ingress`, you basically invoke the stable helm chart:
```
helm install stable/nginx-ingress --name nginx-ingress \
    --set controller.stats.enabled=true \
    --set controller.metrics.enabled=true \
    --set controller.publishService.enabled=true
```


### <a id="extdns"></a> external-dns

In order to use `external-dns`, you should decide whether or not you want to control an existing or a subdomain (safer). In order to create a new subdomain, you can just run:
```
aws route53 create-hosted-zone --name "test.domain.net." --caller-reference "test-domain-$(date +%s)"
```

Copy the displayed `/hostedzone/ROUTE53_ZONEID` into the `external-dns.yaml` file and install it via `kubectl`:
```
kubectl create -f external-dns.yaml
```


### <a id="jenkins"></a> Jenkins

Now that external-dns and nginx-ingress are installed properly, the Jenkins CI can be simple installed using helm a well: `helm install stable/jenkins --name jenkins --values jenkins-values.yaml`

After the DNS kicks in, you can log into the Jenkins on your browser as admin using the auto-generated password: `kubectl get secret --namespace test jenkins -o jsonpath="{.data.jenkins-admin-password}" | base64 --decode | pbcopy`

First thing is the make sure that all the Jenkins URL references are updated. `Manage Jenkins` > `Configure System` and update all Jenkins URLs appropriately to use either the FQDN of the `jenkins.region.test.domain.net` or a CNAME `jenkins.domain.com`

Then, you can add alternate login methods like Google OAuth. `Manage Jenkins` > `Configure Global Security` and modify access control (i.e. for Login with Google, obtain the client id/secret from `console.developers.google.com` and add valid mail domains to the Google Apps Domain box, after you add `http://jenkins.region.test.domain.net/` to the Authorized JavaScript origins
 and `http://jenkins.region.test.domain.net/securityRealm/finishLogin` to the Authorized redirect URIs)

Each repository will have its own Jenkinsfile calling the shared Pipeline in order to handle the deployment steps.


### <a id="docker"></a> Docker

Each repository will have its own Dockerfile to custom build its container for ECR.


### <a id="helm"></a> Helm

Each repository will have its own helm charts to deploy to test/live environments.
