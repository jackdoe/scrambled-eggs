package scrambledeggs

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	qrcode "github.com/skip2/go-qrcode"
	"github.com/ulikunitz/xz"
	"hash/crc32"
	"image"
	"image/gif"
	png "image/png"
	"log"
	"net/http"
)

// format is as follows
// chunk type, chunk id, out of,  data
// n:n:data
// e.g.
// crc32:0:0:1:example.txt:sha:total:type
// crc32:2:0:3:foo            // text
// crc32:2:1:3:bar            // text
// crc32:2:1:3:baz            // text

func compressGzip(b []byte) []byte {
	var buf bytes.Buffer

	zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	zw.Write(b)
	zw.Close()
	return buf.Bytes()
}

func compressXZ(b []byte) []byte {
	var buf bytes.Buffer

	zw, _ := xz.NewWriter(&buf)
	zw.Write(b)
	zw.Close()
	return buf.Bytes()
}

func padRight(str, pad string, lenght int) string {
	if len(str) > lenght {
		return str
	}
	for {
		str += pad
		if len(str) > lenght {
			return str[0:lenght]
		}
	}
}

func encode(recoveryLevel qrcode.RecoveryLevel, chunkSize, width int, chunkType int, chunkId int, outOf int, input []byte) image.Image {
	str := base64.StdEncoding.EncodeToString(input)

	data := fmt.Sprintf("%d:%d:%d:%s", chunkType, chunkId, outOf, str)
	data = padRight(data, "*", chunkSize)

	sum := crc32.ChecksumIEEE([]byte(data))
	data = fmt.Sprintf("%010d:%s", sum, data)

	var qr []byte
	qr, err := qrcode.Encode(data, recoveryLevel, width)
	if err != nil {
		log.Fatal(err)
	}

	//log.Printf("encoding chunk type: %d, id: %d/%d, encoded size: %d, png size %d", chunkType, chunkId, outOf, len(data), len(qr))
	inGif, err := png.Decode(bytes.NewReader(qr))
	if err != nil {
		log.Fatal(err)
	}

	return inGif
}

func addTo(outGif *gif.GIF, delay int, inGif image.Image) {
	outGif.Image = append(outGif.Image, inGif.(*image.Paletted))
	outGif.Delay = append(outGif.Delay, delay)
}

const (
	META = iota
	TEXT = iota
)

type Compression int

const (
	GZIP Compression = iota
	XZ   Compression = iota
	NONE Compression = iota
)

type Config struct {
	Compression   Compression
	Delay         int
	ChunkSize     int
	Width         int
	RecoveryLevel qrcode.RecoveryLevel
}

func NewConfig(width int, delay int, chunkSize int, precoveryLevel string, compression string) *Config {
	recoveryLevel := qrcode.Medium
	if precoveryLevel == "highest" {
		recoveryLevel = qrcode.Highest
	} else if precoveryLevel == "high" {
		recoveryLevel = qrcode.High
	} else if precoveryLevel == "medium" {
		recoveryLevel = qrcode.Medium
	} else if precoveryLevel == "low" {
		recoveryLevel = qrcode.Low
	}

	c := CompressionFromString(compression)
	return &Config{
		Compression:   c,
		RecoveryLevel: recoveryLevel,
		Delay:         delay,
		ChunkSize:     chunkSize,
		Width:         width,
	}
}

func CompressionToString(compression Compression) string {
	if compression == GZIP {
		return "gzip"
	} else if compression == XZ {
		return "xz"
	} else {
		return "none"
	}
}

func CompressionFromString(compression string) Compression {
	if compression == "gzip" {
		return GZIP

	} else if compression == "xz" {
		return XZ
	} else {
		return NONE
	}
}

func Scramble(config *Config, name string, dat []byte) ([]byte, error) {
	contentType := http.DetectContentType(dat)
	if config.Compression == GZIP {
		dat = compressGzip(dat)
	} else if config.Compression == XZ {
		dat = compressXZ(dat)
	}

	outGif := &gif.GIF{}
	h := sha256.New()

	frames := []image.Image{}

	chunks := [][]byte{}

	for i := 0; i < len(dat); i += config.ChunkSize {
		end := i + config.ChunkSize

		if end > len(dat) {
			end = len(dat)
		}
		chunk := dat[i:end]
		chunks = append(chunks, chunk)
	}

	totalChunks := len(chunks)
	c := make(chan image.Image, totalChunks)
	for chunkId, chunk := range chunks {
		h.Write(chunk)
		go func(id int, data []byte) {
			c <- encode(config.RecoveryLevel, config.ChunkSize, config.Width, TEXT, id, totalChunks, data)
		}(chunkId, chunk)
	}

	for i := 0; i < totalChunks; i++ {
		frames = append(frames, <-c)
	}

	frames = append(frames, encode(config.RecoveryLevel, config.ChunkSize, config.Width, META, 0, 1, []byte(fmt.Sprintf("%s:%s:%d:%s:%d:%s", name, hex.EncodeToString(h.Sum(nil)), totalChunks, contentType, len(dat), CompressionToString(config.Compression)))))

	for _, frame := range frames {
		addTo(outGif, config.Delay, frame)
	}

	var buf bytes.Buffer
	writer := bufio.NewWriter(&buf)
	err := gif.EncodeAll(writer, outGif)
	if err != nil {
		return nil, err
	}

	return buf.Bytes(), nil
}
