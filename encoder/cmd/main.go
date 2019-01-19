package main

import (
	"flag"
	"github.com/jackdoe/scrambled-eggs/encoder/scrambledeggs"
	"io"
	"io/ioutil"
	"log"
	"os"
)

func main() {
	var name = flag.String("input", "-", "filename, or - for stdin")
	var outName = flag.String("output", "result.gif", "name for the output gif file")
	var pchunkSize = flag.Int("chunkSize", 1000, "how much information per qrcode")
	var pwidth = flag.Int("gifWidth", 1024, "gif width")
	var pcompression = flag.String("compression", "gzip", "compress the input, supported: gzip, xz, none")
	var pdelay = flag.Int("gifDelay", 80, "gif frame delay")
	var precoveryLevel = flag.String("recoveryLevel", "high", "qrcode recovery level one of [ highest, high, medium, low ]")
	flag.Parse()

	config := scrambledeggs.NewConfig(*pwidth, *pdelay, *pchunkSize, *precoveryLevel, *pcompression)
	var dat []byte
	var err error
	log.Printf("reading: %s -> %s %#v", *name, *outName, config)
	if *name == "-" {
		dat, err = ioutil.ReadAll(os.Stdin)
		if err != nil && err != io.EOF {
			log.Fatal(err)
		}
	} else {
		dat, err = ioutil.ReadFile(*name)
		if err != nil {
			log.Fatal(err)
		}
	}
	encoded, err := scrambledeggs.Scramble(config, *name, dat)
	if err != nil {
		log.Fatal(err)
	}
	err = ioutil.WriteFile(*outName, encoded, 0644)
	if err != nil {
		log.Fatal(err)
	}
}
