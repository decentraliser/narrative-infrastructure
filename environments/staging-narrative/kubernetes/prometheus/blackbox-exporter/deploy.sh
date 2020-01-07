#!/bin/bash
# error if anything bad happens
set -e

echo "Use the staging Kubernetes context..."
kubectl config use-context gke_staging-narrative_us-east1_staging-cluster

echo "Deploying blackbox-exporter-config..."
kubectl -n monitoring create configmap blackbox-exporter-config --from-file=config/config.yml --dry-run -o json | kubectl apply -f -

if kubectl -n monitoring get pods | grep blackbox-exporter; then
  echo "Deleting pod to reload config..."
  kubectl -n monitoring get pods | grep blackbox-exporter | awk '{ print $1 }' | xargs -I {} kubectl -n monitoring delete pod {}
else
  echo "Creating blackbox-exporter deployment."
  kubectl apply -f blackbox-exporter-deployment.yml
fi

echo "Applying the service..."
kubectl apply -f blackbox-exporter-service.yml
