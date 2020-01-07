pipeline {

  agent { label 'narrative-k8s' }

  options {
    skipStagesAfterUnstable()
  }
  post {
    success {
      slackSend channel: "#jenkins",
                color: "good",
                message: "SUCCESS\nJob: <${env.BUILD_URL}|${env.JOB_BASE_NAME} ${env.BUILD_DISPLAY_NAME}>\nThe staging-cluster has been turned ${env.STATUS}."
    }

    failure {
      slackSend channel: "#jenkins",
                color: "danger",
                message: "@here FAILED\nJob: <${env.BUILD_URL}|${env.JOB_BASE_NAME} ${env.BUILD_DISPLAY_NAME}>"
    }

    unstable {
        slackSend channel: "#jenkins",
                color: "danger",
                message: "@here FAILED\nJob: <${env.BUILD_URL}|${env.JOB_BASE_NAME} ${env.BUILD_DISPLAY_NAME}>"
    }
  }
  environment {
      GIT_BRANCH = sh(
          script: '''
            echo $GIT_BRANCH_ORIGIN | awk -F'/' '{ print $NF }'
          ''',
          returnStdout: true
          ).trim()
  }
  stages {
    stage('code-checkout') {
      steps {
         checkout scm: [
             $class: 'GitSCM',
             branches: [[name: "${env.GIT_BRANCH_ORIGIN}"]],
             doGenerateSubmoduleConfigurations: false,
             submoduleCfg: [],
             userRemoteConfigs: [[credentialsId: '24489ad6-f93a-4682-8ac3-cdeb6de18c71',
                                  url: 'git@github.com:NarrativeCompany/narrative-infrastructure.git']]
         ]
      }
    }
    stage('package-install') {
      steps {
        withCredentials([
          file(credentialsId: 'bot_ops_ssh_key', variable: 'BOT_OPS_SSH_KEY')
        ])
        {
          script {
            sh(
              script: '''#!/bin/bash
              set -ex
              apt-get update && apt-get install -y ed
              wget https://releases.hashicorp.com/terraform/0.11.11/terraform_0.11.11_linux_amd64.zip
              unzip terraform_0.11.11_linux_amd64.zip
              install terraform /usr/local/bin/
            ''',
            )
          }
        }
      }
    }
    stage('staging-narrative-power-button') {
      steps {
        withCredentials([
          file(credentialsId: 'gcloud-jenkins-file', variable: 'GCLOUD_JENKINS_FILE'),
          file(credentialsId: 'ansible_vault_ops', variable: 'ANSIBLE_VAULT_OPS'),
          file(credentialsId: 'bot_ops_ssh_key', variable: 'BOT_OPS_SSH_KEY')
        ])
        {
          script {
            sh(
              script: '''#!/bin/bash
              set -ex 
              export GCE_EMAIL=jenkins@informationtechnology-205813.iam.gserviceaccount.com
              export GCE_CREDENTIALS_FILE_PATH=${GCLOUD_JENKINS_FILE}
              gcloud auth activate-service-account jenkins@informationtechnology-205813.iam.gserviceaccount.com --key-file=${GCLOUD_JENKINS_FILE}
              export GOOGLE_APPLICATION_CREDENTIALS=${GCLOUD_JENKINS_FILE}
              export GOOGLE_PROJECT=staging-narrative

              # Set gcloud project to staging-narrative
              gcloud config set project staging-narrative

              if [ ${STATUS} = 'on' ]; then

                # Create staging redis
                cd environments/staging-narrative/terraform/
                terraform init
                terraform apply -auto-approve -target=google_redis_instance.redis

                # Start solr VMs
                gcloud compute instances start --project staging-narrative --zone=us-east1-b solr-01
                gcloud compute instances start --project staging-narrative --zone=us-east1-c solr-02
                gcloud compute instances start --project staging-narrative --zone=us-east1-d solr-03

                # Set staging GKE cluster to size 1
                gcloud container clusters resize --quiet --region=us-east1 staging-cluster --size=1

                # Add federation-staging job to primary prometheus
                gcloud beta container clusters get-credentials it-cluster --region us-east1 --project informationtechnology-205813
                kubectl config use-context gke_informationtechnology-205813_us-east1_it-cluster
                cd ../../informationtechnology-205813/prometheus/prometheus-primary/

                # Export the live prometheus-primary config
                kubectl -n it get configmap prometheus-config --export -o yaml > prometheus.yml

                # Delete the first three lines.
                sed -i '1,3d' prometheus.yml

                # Delete the "kind: ConfigMap" line and all lines after it.
                sed -i '/kind: ConfigMap/,$d' prometheus.yml

                # Remove four spaces from the beginning of each line.
                sed -i 's/    //' prometheus.yml

                # Insert federation-staging into prometheus-primary config.
                echo -e "/- job_name: 'federation-production'/-1r config/federation-staging.yml\n%w" | ed -s prometheus.yml

                # Deploying prometheus-config.
                kubectl -n it create configmap prometheus-config --from-file=prometheus.yml  --dry-run -o json | kubectl apply -f -

                # Deploying prometheus-rules.
                kubectl -n it create configmap prometheus-rules --from-file=rules/ --dry-run -o json | kubectl apply -f -

                # Pause 60 seconds to let configmaps update.
                sleep 60

                # Reloading config.
                kubectl -n it get pods | grep prometheus | awk '{ print $1 }' | xargs -I {} kubectl -n it exec {} -- /bin/sh -c "kill -HUP 1"

              elif [ ${STATUS} = 'off' ]; then

                # Remove federation-staging job from primary prometheus
                gcloud beta container clusters get-credentials it-cluster --region us-east1 --project informationtechnology-205813
                kubectl config use-context gke_informationtechnology-205813_us-east1_it-cluster
                cd environments/informationtechnology-205813/prometheus/prometheus-primary/

                # Export the live prometheus-primary config
                kubectl -n it get configmap prometheus-config --export -o yaml > prometheus.yml

                # Delete the first three lines.
                sed -i '1,3d' prometheus.yml

                # Delete the "kind: ConfigMap" line and all lines after it.
                sed -i '/kind: ConfigMap/,$d' prometheus.yml

                # Remove four spaces from the beginning of each line.
                sed -i 's/    //' prometheus.yml

                # Remove federation-staging job from primary prometheus.
                sed -i "/- job_name: 'federation-staging'/,/cluster: 'narrative-staging-cluster'/d" prometheus.yml

                # Deploying prometheus-config.
                kubectl -n it create configmap prometheus-config --from-file=prometheus.yml  --dry-run -o json | kubectl apply -f -

                # Deploying prometheus-rules.
                kubectl -n it create configmap prometheus-rules --from-file=rules/ --dry-run -o json | kubectl apply -f -

                # Pause 60 seconds to let configmaps update.
                sleep 60

                # Reloading config.
                kubectl -n it get pods | grep prometheus | awk '{ print $1 }' | xargs -I {} kubectl -n it exec {} -- /bin/sh -c "kill -HUP 1"

                # Set staging GKE cluster to size 0
                gcloud container clusters resize --quiet --region=us-east1 staging-cluster --size=0

                # Stop solr VMs
                gcloud compute instances stop --project staging-narrative --zone=us-east1-b solr-01
                gcloud compute instances stop --project staging-narrative --zone=us-east1-c solr-02
                gcloud compute instances stop --project staging-narrative --zone=us-east1-d solr-03

                # Destroy staging redis
                cd ../../../staging-narrative/terraform/
                terraform init
                terraform destroy -auto-approve -target=google_redis_instance.redis

              fi
            ''',
            )
          }
        }
      }
    }
    stage ('ssh-known-hosts-update') {
      when {
        expression {
          STATUS == 'on'
        }
      }
      steps {
        build job: "/ssh-known-hosts-update", parameters: [[$class: 'StringParameterValue', name: 'GIT_BRANCH_ORIGIN', value: GIT_BRANCH_ORIGIN]]
      }
    }
  }
}

