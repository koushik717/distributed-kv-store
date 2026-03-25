#!/bin/bash
# deploy.sh — Build and deploy kv-store to local minikube
set -e

echo "==> Pointing Docker CLI at minikube's daemon..."
# This makes 'docker build' put the image inside minikube — no registry needed.
eval $(minikube docker-env)

echo "==> Building kv-store image inside minikube..."
cd "$(dirname "$0")/.."
docker build -t kv-store:latest .

echo "==> Applying K8s manifests..."
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/node1.yaml
kubectl apply -f k8s/node2.yaml
kubectl apply -f k8s/node3.yaml
kubectl apply -f k8s/node1-nodeport.yaml

echo "==> Waiting for pods to be ready..."
kubectl rollout status deployment/kv-node1 -n kv-store
kubectl rollout status deployment/kv-node2 -n kv-store
kubectl rollout status deployment/kv-node3 -n kv-store

echo ""
echo "==> All pods running:"
kubectl get pods -n kv-store
echo ""
echo "==> Services:"
kubectl get svc -n kv-store
echo ""
echo "==> To access node1 from your browser:"
echo "    kubectl port-forward svc/node1-external 8080:8080 -n kv-store"
echo "    Then open: http://localhost:8080/admin/status"
