package main

import (
	"flag"
	"github.com/gin-gonic/gin"
	"github.com/jackdoe/scrambled-eggs/encoder/scrambledeggs"
	"io"
	"io/ioutil"
	//"log"
	//	"os"
)

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
	router.MaxMultipartMemory = 1024 * 100 // 100 kb
	router.Static("/", "./public")
	router.POST("/upload", func(c *gin.Context) {
		file, err := c.FormFile("file")
		if err != nil {
			panic(err)
		}

		src, err := file.Open()
		if err != nil {
			panic(err)
		}
		defer src.Close()

		dat, err := ioutil.ReadAll(src)
		if err != nil && err != io.EOF {
			panic(err)
		}
		res, err := scrambledeggs.Scramble(config, file.Filename, dat)
		if err != nil && err != io.EOF {
			panic(err)
		}

		c.Data(200, "image/gif", res)
	})
	router.Run(*pbind)
}
