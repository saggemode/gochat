# ☸️ GoChat Kubernetes (K8s) Deployment Guide

This directory contains production-ready Kubernetes manifests and Kustomize configurations for deploying the **GoChat** backend microservices platform.

---

## 📁 Architecture & File Structure

```
k8s/
├── namespace.yaml              # Creates the 'gochat' namespace
├── configmap.yaml              # Cluster-wide gRPC endpoints, ports & settings
├── secrets.yaml                # Passwords, JWT secrets, DSNs (change for production)
├── kustomization.yaml          # Root Kustomize file (1-command deployment)
│
├── storage/                    # StatefulSets & Volume Claims
│   ├── postgres.yaml           # PostgreSQL (PostGIS) + PVC
│   ├── redis.yaml              # Redis Pub/Sub + PVC
│   └── minio.yaml              # MinIO S3 Object Storage + PVC
│
├── microservices/              # gRPC Internal Microservices
│   ├── auth.yaml               # Auth Service + gRPC port 50051
│   ├── authz.yaml              # Authorization Service (50054)
│   ├── chat.yaml               # Chat Service (50052) + HPA Auto-scaler
│   ├── media.yaml              # Media Service (50053)
│   ├── group.yaml              # Group Service (50055)
│   ├── story.yaml              # Story/Status Service (50056)
│   ├── call.yaml               # WebRTC Calling Service (50057)
│   ├── channel.yaml            # Channels Service (50058)
│   ├── social.yaml             # Social & Friend Service (50061)
│   ├── miniapp.yaml            # MiniApp Store Service (50062)
│   └── business.yaml           # Business Hub Service (50063)
│
└── gateway/                    # Public API Edge
    ├── gateway.yaml            # API Gateway Deployment + HPA + ClusterIP
    └── ingress.yaml            # Ingress rules with WebSocket & 64MB upload support
```

---

## 🚀 Quickstart: Deploying to Kubernetes

### 1. Build and Push Docker Images
Build the container images for your target registry (e.g., Docker Hub, AWS ECR, GCR, or GitHub Container Registry):

```bash
# Example for Docker Hub (replace 'your-dockerhub-username' with your username)
export DOCKER_USER=your-dockerhub-username

for svc in auth authz chat media group story call channel social miniapp business gateway; do
  docker build -t $DOCKER_USER/gochat-$svc:latest --build-arg SERVICE=$svc .
  docker push $DOCKER_USER/gochat-$svc:latest
done
```

> **Tip**: If using Minikube locally, you can load images directly into Minikube without pushing to a registry:
> ```bash
> eval $(minikube docker-env)
> for svc in auth authz chat media group story call channel social miniapp business gateway; do
>   docker build -t gochat/$svc:latest --build-arg SERVICE=$svc .
> done
> ```

---

### 2. Configure Secrets
Open [`k8s/secrets.yaml`](./secrets.yaml) and update:
* `JWT_SECRET`: A secure random 32+ character string
* `POSTGRES_PASSWORD`: Your PostgreSQL password
* `REDIS_PASSWORD`: Your Redis authentication password
* `MINIO_SECRET_KEY`: MinIO S3 secret key

---

### 3. Deploy with a Single Command
Apply all manifests using Kustomize:

```bash
kubectl apply -k k8s/
```

Check the rollout status of all pods:

```bash
kubectl get pods -n gochat -w
```

---

### 4. Verify Services and Ingress

```bash
# Check running services
kubectl get svc -n gochat

# Check ingress
kubectl get ingress -n gochat
```

For local clusters without an Ingress controller (e.g. Minikube / Kind), forward the gateway port:

```bash
kubectl port-forward svc/gateway-svc -n gochat 8080:8080
```

Now the GoChat API & WebSocket gateway is accessible at `http://localhost:8080` (or `ws://localhost:8080/ws`).

---

## ⚙️ Cloud Provider Customizations

### Using Managed Database (AWS RDS / Supabase / Neon)
To use a managed cloud PostgreSQL database instead of the in-cluster PostgreSQL StatefulSet:
1. Update `AUTH_DB_DSN`, `CHAT_DB_DSN`, etc. in [`k8s/secrets.yaml`](./secrets.yaml) with your cloud database connection string.
2. Remove `- storage/postgres.yaml` from [`k8s/kustomization.yaml`](./kustomization.yaml).

### Using AWS S3 instead of MinIO
1. Update `MINIO_ENDPOINT` in [`k8s/configmap.yaml`](./configmap.yaml) to `s3.amazonaws.com` and `MINIO_USE_SSL: "true"`.
2. Update `MINIO_ACCESS_KEY` and `MINIO_SECRET_KEY` in [`k8s/secrets.yaml`](./secrets.yaml) with your AWS IAM credentials.
3. Remove `- storage/minio.yaml` from [`k8s/kustomization.yaml`](./kustomization.yaml).

---

## 🧹 Teardown / Cleanup

To delete all GoChat Kubernetes resources:

```bash
kubectl delete -k k8s/
```
