# Kubernetes proxy fleet

This StatefulSet models a reusable token authorized to attach replicas to one
existing signalling service. Scaling from three to five creates two independently
keyed instances at PoW difficulty zero. Scaling down sends SIGTERM; Geyser waits for
provider drain before the pod exits. Per-pod persistent state lets the same ordinal
recover its identity when restarted.

Create the token without committing it, then apply and scale:

```sh
kubectl create secret generic nethernet-proxy-provider --from-literal=token='replace-me'
kubectl apply -f statefulset.yml
kubectl scale statefulset nethernet-proxy --replicas=5
kubectl scale statefulset nethernet-proxy --replicas=2
```

Replace the image and storage class for your platform, and `/opt/geyser` with the image's Geyser working directory. The manifest demonstrates
registration lifecycle only: production also needs routable UDP addressing for
each advertised native candidate. For Warden, delegate exactly `EU`, `proxy`,
`location=london`, `role=game-proxy` to the service-scoped token before deploying.
