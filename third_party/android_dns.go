// Copyright 2026 OpenShelf contributors
// SPDX-License-Identifier: Apache-2.0
//
// Android builds of cloudflared have no usable /etc/resolv.conf, and the
// pure-Go resolver then falls back to 127.0.0.1:53, which phones do not run.
// This file is copied into cmd/cloudflared/ only for the OpenShelf Android
// build. It is not part of upstream cloudflared.
//
// Quick Tunnel still has to resolve api.trycloudflare.com and the edge
// hostnames. We send those lookups to public resolvers. The Dial target is a
// literal IP, so this does not recurse through DefaultResolver.

package main

import (
	"context"
	"net"
	"strings"
	"time"
)

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial:     dialPublicDNS,
	}
}

func dialPublicDNS(ctx context.Context, network, _ string) (net.Conn, error) {
	dialer := net.Dialer{Timeout: 5 * time.Second}
	proto := "udp"
	if strings.HasPrefix(network, "tcp") {
		proto = "tcp"
	}
	var last error
	for _, server := range []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"} {
		conn, err := dialer.DialContext(ctx, proto, server)
		if err == nil {
			return conn, nil
		}
		last = err
	}
	return nil, last
}
