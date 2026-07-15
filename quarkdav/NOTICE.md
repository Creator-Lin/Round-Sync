# QuarkDav cookie kernel notice

The cookie-backed WebDAV kernel in this directory is derived from the
`QuarkDav-Android` source archive supplied for this integration.

The following source areas were retained for the cookie driver and adapted to
run as an independent Android-native executable inside Round-Sync:

- `internal/dav`
- `internal/drive`
- `internal/quarkcookie`
- `internal/remotefs`
- `internal/util`
- the cookie-related portions of `internal/app`

The Open API and TV drivers were intentionally not included.

The supplied QuarkDav-Android project is licensed under the GNU Affero General
Public License, version 3. A verbatim copy is available as
`LICENSE-AGPL-3.0` in this directory. Modifications to this component remain
subject to that license. Round-Sync's existing licensing and notices continue
to apply to the rest of the project.
