# 🌐 Hosted WebRTC Signaling Server Deployment Guide

## Overview
This signaling server routes **SDP Offers**, **SDP Answers**, and **ICE Candidates** between peers so two computers anywhere across the Internet can discover each other and open a direct **WebRTC DataChannel**.

> [!NOTE]
> **Zero Chat Traffic Passes Through This Server**:
> Once the WebRTC P2P DataChannel connects, all text messages and file transfers travel directly peer-to-peer!

---

## 🚀 Option 1: Run with Java directly on your VPS

On your remote VPS (Ubuntu/Debian):
```bash
# 1. Install Java 21 JRE
sudo apt update && sudo apt install -y openjdk-21-jre-headless

# 2. Upload and Run JAR
java -cp Project-CX-1.0-SNAPSHOT.jar org.yu.projectcx.network.signaling.HostedSignalingServer 8888
```

---

## 🐳 Option 2: Run via Docker

```bash
# Build image
docker build -t projectcx-signaling -f server/Dockerfile .

# Run container exposing port 8888
docker run -d --name signaling-server -p 8888:8888 projectcx-signaling
```

---

## 🔒 Firewall / Security Groups

Ensure port `8888` (TCP) is open on your VPS firewall / AWS Security Group:
```bash
sudo ufw allow 8888/tcp
```
