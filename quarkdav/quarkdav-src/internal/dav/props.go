package dav

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"html"
	"net/http"
	"path"
	"strings"
	"time"

	"quarkdav/internal/drive"
	"quarkdav/internal/util"
)

type propfindReq struct {
	XMLName  xml.Name  `xml:"DAV: propfind"`
	Propname *struct{} `xml:"DAV: propname"`
	Allprop  *struct{} `xml:"DAV: allprop"`
	Prop     struct {
		Props []xml.Name `xml:",any"`
	} `xml:"DAV: prop"`
}

type proppatchReq struct {
	XMLName xml.Name   `xml:"DAV: propertyupdate"`
	Any     []xml.Name `xml:",any"`
}

func readPropfind(body []byte) (propfindReq, error) {
	if len(bytes.TrimSpace(body)) == 0 {
		return propfindReq{Allprop: &struct{}{}}, nil
	}
	var pf propfindReq
	if err := xml.Unmarshal(body, &pf); err != nil {
		return pf, err
	}
	if pf.Propname == nil && pf.Allprop == nil && len(pf.Prop.Props) == 0 {
		pf.Allprop = &struct{}{}
	}
	return pf, nil
}

var livePropNames = []xml.Name{
	{Space: "DAV:", Local: "resourcetype"},
	{Space: "DAV:", Local: "displayname"},
	{Space: "DAV:", Local: "getcontentlength"},
	{Space: "DAV:", Local: "getlastmodified"},
	{Space: "DAV:", Local: "creationdate"},
	{Space: "DAV:", Local: "getcontenttype"},
	{Space: "DAV:", Local: "getetag"},
	{Space: "DAV:", Local: "supportedlock"},
	{Space: "DAV:", Local: "lockdiscovery"},
	{Space: "http://owncloud.org/ns", Local: "checksums"},
}

func propAllowedFor(e *drive.Entry, n xml.Name) bool {
	switch n.Local {
	case "getcontentlength", "getcontenttype", "getetag", "checksums":
		return !e.IsDir
	default:
		return true
	}
}

func writePropfindResponse(ctx context.Context, h *Handler, b *bytes.Buffer, href string, e *drive.Entry, pf propfindReq, userAgent string) {
	b.WriteString("<D:response>")
	b.WriteString("<D:href>")
	b.WriteString(xmlEscapeURL(href))
	b.WriteString("</D:href>")
	if pf.Propname != nil {
		b.WriteString("<D:propstat><D:prop>")
		for _, pn := range livePropNames {
			if propAllowedFor(e, pn) {
				writeEmptyPropName(b, pn)
			}
		}
		b.WriteString("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
	} else {
		names := pf.Prop.Props
		if pf.Allprop != nil {
			names = livePropNames
		}
		b.WriteString("<D:propstat><D:prop>")
		missing := make([]xml.Name, 0)
		for _, pn := range names {
			if !propAllowedFor(e, pn) {
				missing = append(missing, pn)
				continue
			}
			if !writePropValue(ctx, h, b, pn, e, userAgent) {
				missing = append(missing, pn)
			}
		}
		b.WriteString("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
		if len(missing) > 0 {
			b.WriteString("<D:propstat><D:prop>")
			for _, pn := range missing {
				writeEmptyPropName(b, pn)
			}
			b.WriteString("</D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>")
		}
	}
	b.WriteString("</D:response>")
}

func writePropValue(ctx context.Context, h *Handler, b *bytes.Buffer, pn xml.Name, e *drive.Entry, userAgent string) bool {
	switch pn.Space + pn.Local {
	case "DAV:" + "resourcetype":
		b.WriteString("<D:resourcetype>")
		if e.IsDir {
			b.WriteString("<D:collection/>")
		}
		b.WriteString("</D:resourcetype>")
	case "DAV:" + "displayname":
		b.WriteString("<D:displayname>")
		b.WriteString(html.EscapeString(e.DisplayName()))
		b.WriteString("</D:displayname>")
	case "DAV:" + "getcontentlength":
		b.WriteString(fmt.Sprintf("<D:getcontentlength>%d</D:getcontentlength>", e.Size))
	case "DAV:" + "getlastmodified":
		b.WriteString("<D:getlastmodified>")
		b.WriteString(e.ModTime.UTC().Format(http.TimeFormat))
		b.WriteString("</D:getlastmodified>")
	case "DAV:" + "creationdate":
		b.WriteString("<D:creationdate>")
		if strings.Contains(strings.ToLower(userAgent), "microsoft-webdav") {
			b.WriteString(e.CreateTime.UTC().Format(http.TimeFormat))
		} else {
			b.WriteString(e.CreateTime.UTC().Format(time.RFC3339))
		}
		b.WriteString("</D:creationdate>")
	case "DAV:" + "getcontenttype":
		b.WriteString("<D:getcontenttype>")
		b.WriteString(util.MIMEByName(e.Name))
		b.WriteString("</D:getcontenttype>")
	case "DAV:" + "getetag":
		b.WriteString("<D:getetag>")
		b.WriteString(e.ETag())
		b.WriteString("</D:getetag>")
	case "DAV:" + "supportedlock":
		b.WriteString("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
	case "DAV:" + "lockdiscovery":
		b.WriteString("<D:lockdiscovery>")
		for _, li := range h.Locks.Active(path.Join("/", e.Name)) {
			_ = li
		}
		b.WriteString("</D:lockdiscovery>")
	case "http://owncloud.org/ns" + "checksums":
		b.WriteString("<oc:checksums xmlns:oc=\"http://owncloud.org/ns\">")
		if e.ContentHash != "" {
			b.WriteString("<oc:checksum>MD5:" + html.EscapeString(e.ContentHash) + "</oc:checksum>")
		}
		b.WriteString("</oc:checksums>")
	default:
		return false
	}
	return true
}

func writeEmptyPropName(b *bytes.Buffer, pn xml.Name) {
	prefix := "D"
	if pn.Space == "http://owncloud.org/ns" {
		prefix = "oc"
	}
	b.WriteString("<" + prefix + ":" + pn.Local)
	if prefix == "oc" {
		b.WriteString(" xmlns:oc=\"http://owncloud.org/ns\"")
	}
	b.WriteString("/>")
}

func xmlEscapeURL(s string) string { return html.EscapeString(s) }

func propPatchForbidden(w http.ResponseWriter, href string, body []byte) {
	var pr proppatchReq
	_ = xml.Unmarshal(body, &pr)
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.WriteHeader(207)
	_, _ = w.Write([]byte(xml.Header + `<D:multistatus xmlns:D="DAV:"><D:response><D:href>` + html.EscapeString(href) + `</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat></D:response></D:multistatus>`))
}
