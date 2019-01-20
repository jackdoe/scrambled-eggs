package main

import (
	"flag"
	"github.com/gin-gonic/gin"
	"github.com/jackdoe/scrambled-eggs/encoder/scrambledeggs"
	"io"
	"io/ioutil"
	"log"
	"net/http"
)

// taken from https://github.com/aviddiviner/gin-limit/blob/master/limit.go
func MaxAllowed(n int, f gin.HandlerFunc) gin.HandlerFunc {
	sem := make(chan struct{}, n)
	acquire := func() { sem <- struct{}{} }
	release := func() { <-sem }
	return func(c *gin.Context) {
		acquire()       // before request
		defer release() // after request
		f(c)
	}
}

func main() {

	var pchunkSize = flag.Int("chunkSize", 1800, "how much information per qrcode")
	var pwidth = flag.Int("gifWidth", 900, "gif width")
	var pcompression = flag.String("compression", "gzip", "compress the input, supported: gzip, xz, none")
	var pdelay = flag.Int("gifDelay", 50, "gif frame delay")
	var precoveryLevel = flag.String("recoveryLevel", "low", "qrcode recovery level one of [ highest, high, medium, low ]")
	var pbind = flag.String("bind", ":8080", "bind to address and port")
	flag.Parse()

	router := gin.Default()
	config := scrambledeggs.NewConfig(*pwidth, *pdelay, *pchunkSize, *precoveryLevel, *pcompression)
	router.MaxMultipartMemory = 1024 * 1024 // 1mb, rest goes to file
	router.Static("/", "./public")
	router.POST("/upload", MaxAllowed(2, func(c *gin.Context) {
		file, err := c.FormFile("file")
		if err != nil {
			log.Printf("err: %s", err.Error())
			c.String(http.StatusInternalServerError, err.Error())
			return

		}

		src, err := file.Open()
		if err != nil {
			log.Printf("err: %s", err.Error())
			c.String(http.StatusInternalServerError, err.Error())
			return
		}
		defer src.Close()

		dat, err := ioutil.ReadAll(src)
		if err != nil && err != io.EOF {
			log.Printf("err: %s", err.Error())
			c.String(http.StatusInternalServerError, err.Error())
			return
		}
		res, err := scrambledeggs.Scramble(config, file.Filename, dat)
		if err != nil && err != io.EOF {
			log.Printf("err: %s", err.Error())
			c.String(http.StatusInternalServerError, err.Error())
			return
		}

		c.Data(200, "image/gif", res)
	}))
	router.Run(*pbind)
}
