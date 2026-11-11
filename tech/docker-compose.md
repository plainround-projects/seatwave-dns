# Docker Compose and YAML (the optional `compose.yaml`)

## What it is

**Docker** runs programs in **containers**. A container is a sealed, lightweight box that holds one program together with the exact files it needs, so it runs the same everywhere. A container is created from an **image**, a read-only package. Images are stored in a **registry** such as Docker Hub and are named `name:tag`, where the tag picks a version.

**Docker Compose** reads a text file that describes one or more containers and starts them with one command, `docker compose up`. The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks: `key: value` pairs, **lists** (lines that start with `- `) and **comments** (`#` to the end of the line). Tabs are not allowed for indentation.

**This path was not run on the verification machine.** Docker was not running there, and the brief for this module says not to start it. Everything below comes from reading the file and the README, not from running a container. The README records the failed check: `docker info` printed `ERROR: Cannot connect to the Docker daemon`.

## Why this repository uses it

It gives a learner who has Docker a way to run a real, widely used name server (CoreDNS, `coredns.md`) on the same three ports with the same zone files as the Java server, so that `dig` and the sampler can be pointed at it. The README marks it "optional" and "[not run]".

## Where it appears

| Path | What it does |
| --- | --- |
| `compose.yaml` | The only Compose file: one service, `coredns`. |
| `coredns/Corefile` | Mounted into the container as its settings (`coredns.md`). |
| `zones/` | Mounted read-only as `/zones`. |

## How it works underneath

1. `docker compose up` reads `compose.yaml` in the current folder.
2. For the service `coredns` it pulls the image named in `image:` from the registry if it is not on the machine yet, and checks that the image's digest is the pinned one.
3. It creates a container, mounts the volumes, publishes the ports and starts the container with the given command-line arguments.
4. The container's program prints to your terminal. Press Ctrl-C or run `docker compose down` to stop it.
5. A **bind mount** shows a file or folder of your machine inside the container. The suffix `:ro` makes it read-only inside. **Publishing a port** (`host:container`) connects a port on your machine to a port in the container.

An **image digest** is a fingerprint (a SHA-256 hash) of the exact image content. A tag such as `1.15.0` is a label that its owner could move to a different image later. A digest cannot move: a changed image has a different digest. Writing `name:tag@sha256:...` means "this tag, and I insist on exactly this content", so a pulled image that differs is refused.

## Walkthrough of this repository's use

```yaml
# Optional: the production-like layer. CoreDNS serves the same zone files as the Java lab server,
# on the same three ports, bound to 127.0.0.1 only, so dig and the sampler can be pointed at it.
# The image tag and its digest were checked on Docker Hub on 10 November 2026 (CoreDNS 1.15.0,
# released 21 October 2026). Start it with: docker compose up
services:
  coredns:
    image: coredns/coredns:1.15.0@sha256:0a9d8c8ddf34347eb59aad9a8784f0710de88d6d112726d11d271f9bc6daca26
    command: ["-conf", "/etc/coredns/Corefile"]
    volumes:
      - ./coredns/Corefile:/etc/coredns/Corefile:ro
      - ./zones:/zones:ro
    ports:
      - "127.0.0.1:5301:5301/udp"
      - "127.0.0.1:5302:5302/udp"
      - "127.0.0.1:5303:5303/udp"
    read_only: true
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
```

- Lines 1 to 4 are comments: the layer is optional, CoreDNS serves the same zone files as the Java lab server on the same three ports bound to `127.0.0.1` only, the tag and digest were checked on Docker Hub on 10 November 2026 (CoreDNS 1.15.0, released 21 October 2026), and the start command is `docker compose up`.
- `services:` starts the list of containers. Everything indented below it is a service.
- `coredns:` is the name of the one service. The name is yours.
- `image: coredns/coredns:1.15.0@sha256:0a9d8c8d...caca26` names the image. `coredns/coredns` is the publisher and image name on Docker Hub, `1.15.0` is the tag, and `@sha256:` followed by 64 hexadecimal characters is the digest pin explained above.
- `command: ["-conf", "/etc/coredns/Corefile"]` gives the container its arguments, written as a YAML list on one line (square brackets, items separated by commas). They are passed to the program in the image, which is CoreDNS; `-conf` tells it which Corefile to read.
- `volumes:` lists mounts as `source:target:options`.
  - `- ./coredns/Corefile:/etc/coredns/Corefile:ro` mounts the Corefile of the repository at the path given to `-conf`, read-only.
  - `- ./zones:/zones:ro` mounts the zones folder at `/zones`, read-only. The Corefile refers to `/zones/...`.
- `ports:` publishes the three UDP ports:
  - `- "127.0.0.1:5301:5301/udp"` means: on the host address `127.0.0.1` port 5301, forward to container port 5301, UDP. The quotes keep YAML from reading the colons as something else. The same for 5302 and 5303. Binding to `127.0.0.1` means that only programs on this computer can reach the server.
- `read_only: true` makes the container's own file system read-only. The program cannot write anywhere except where Docker gives it a writable area.
- `cap_drop:` followed by `- ALL` removes every Linux **capability** from the container. A capability is one special privilege of the administrator, such as binding to a port below 1024. CoreDNS here uses ports above 1024, so it needs none.
- `security_opt:` followed by `- no-new-privileges:true` forbids the program from gaining extra privileges later (for example through a set-user-id program).

I read the file with Ruby's YAML reader on this machine (no Docker needed). It parses to one service `coredns` with the image string above, `command` a list of two strings, `volumes` a list of two strings, `ports` a list of three strings, `read_only` true, `cap_drop` `["ALL"]` and `security_opt` `["no-new-privileges:true"]`.

## Try it yourself

I did not run Docker. These commands read the file only:

1. Parse the file as YAML:

```
ruby -ryaml -rjson -e 'puts JSON.generate(YAML.load_file("compose.yaml"))'
```

   Output: one line of JSON that begins `{"services":{"coredns":{"image":"coredns/coredns:1.15.0@sha256:0a9d8c8d...` and ends `"read_only":true,"cap_drop":["ALL"],"security_opt":["no-new-privileges:true"]}}}`.

2. Show the digest pin:

```
grep -o '@sha256:[0-9a-f]*' compose.yaml | awk '{print length($0)-8}'
```

   Output: `64`, the length of a SHA-256 in hexadecimal.

3. Count the published ports:

```
grep -c '127.0.0.1:530' compose.yaml
```

   Output: `3`.

4. With Docker available (not run here): `docker compose config` would print the file as Compose understands it, and `docker compose up` would start CoreDNS.

## Mistakes people make

- **Tabs in YAML.** Only spaces. The `.editorconfig` forces two spaces for `yaml` (`editorconfig.md`).
- **Unquoted port mappings.** `127.0.0.1:5301:5301/udp` with the colons can be misread. The file quotes them.
- **Publishing on `0.0.0.0` by leaving out the address.** Then other machines could reach the server. Here every port starts with `127.0.0.1`.
- **Pinning by tag only.** A tag can move. The digest cannot.
- **Forgetting `/udp`.** The default is TCP, and DNS queries here are UDP.
- **Running both servers at once.** The Java lab server and the container use the same ports.

## Check yourself

1. *What does the digest after `@sha256:` protect against?* Getting a different image under the same tag.
2. *What does `:ro` do on a volume?* Makes it read-only inside the container.
3. *Why is every port written with `127.0.0.1` in front?* So only this computer can reach the server.
4. *What does `cap_drop: - ALL` do?* Removes all special administrator privileges from the container.
5. *Was this file run for these notes?* No. Docker was not running.

## Further reading

- Compose file reference: <https://docs.docker.com/reference/compose-file/>
- Image digests: <https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier>
- YAML 1.2 specification: <https://yaml.org/spec/1.2.2/>
