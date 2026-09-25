FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/im-logic ./cmd/logic && \
    CGO_ENABLED=0 go build -o /out/im-gateway ./cmd/gateway

FROM alpine:3.20
RUN apk add --no-cache ca-certificates mysql-client
COPY --from=build /out/im-logic /usr/local/bin/im-logic
COPY --from=build /out/im-gateway /usr/local/bin/im-gateway
COPY deploy/schema.sql /usr/local/share/im/schema.sql
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
