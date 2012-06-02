(import 
  '(java.awt Rectangle Robot Toolkit)
  '(java.awt.image BufferedImage)
  '(java.io File BufferedReader InputStreamReader PrintWriter)
  '(javax.imageio ImageIO)
  '(java.net ServerSocket)
  '(com.xuggle.xuggler IContainer IContainer$Type ICodec ICodec$ID ICodec$Type IStreamCoder$Flags 
                       IPixelFormat$Type IRational IPacket IContainerFormat)
  '(com.xuggle.xuggler.video ConverterFactory))

(def exit? (atom false))
(def snap-time (atom -1))
(def frames (atom 0))
(def reframe (atom nil))

(defn take-screenshot []
  (let [screen (.getScreenSize (Toolkit/getDefaultToolkit)) 
        rt (new Robot)]
    (.createScreenCapture rt (new Rectangle (int (.getWidth screen)) (int (.getHeight screen))))))

(defn save-image [buf]
  (ImageIO/write buf "jpg" (new File (str (System/currentTimeMillis) ".jpeg"))))

(defn file-container
  "file based container for testing"
  []
  (let [container (IContainer/make)]
    (if (< (.open container "foo.ogv" IContainer$Type/WRITE nil) 0)
      (throw (RuntimeException. "Failed opening file container"))
      container)))

(defn make-container
  "create container for video stream into socket"
  [out-stream]
  (let [container (doto (IContainer/make))
        container-format (doto (IContainerFormat/make) (.setOutputFormat "ogv" "foo.ogv" "video/ogg") 
                           (.establishOutputCodecId ICodec$Type/CODEC_TYPE_VIDEO))]
    (when (< (.open container out-stream container-format) 0)
      (throw (RuntimeException. "Failed opening container")))
    container))

(defn make-stream
  "create stream from container"
  [container frameRate]
  (let [codec (ICodec/guessEncodingCodec nil nil "foo.ogv" nil ICodec$Type/CODEC_TYPE_VIDEO) 
        ;codec (ICodec/findEncodingCodec ICodec$ID/CODEC_ID_THEORA)
        screen (.getScreenSize (Toolkit/getDefaultToolkit))
        outStream (.addNewStream container codec)
        outCoder (.getStreamCoder outStream)]
    (if (= codec nil)
      (throw (RuntimeException. "Failed guessing codec"))
      (doto outCoder (.setNumPicturesInGroupOfPictures 1) (.setCodec codec) (.setBitRate 25000) 
        (.setBitRateTolerance 9000) (.setPixelType IPixelFormat$Type/YUV420P) (.setWidth (int (.getWidth screen)))
        (.setHeight (int (.getHeight screen))) (.setFlag IStreamCoder$Flags/FLAG_QSCALE true) (.setGlobalQuality 0)
        (.setFrameRate frameRate) (.setAutomaticallyStampPacketsForStream true)
        (.setTimeBase (IRational/make (.getDenominator frameRate) (.getNumerator frameRate)))))))

(defn convert-image
  "convert iimage into usable format"
  [image target-type]
  (if (= (.getType image) target-type)
    image
    (let [img (BufferedImage. (.getWidth image) (.getHeight image) target-type)]
      (.drawImage (.getGraphics img) image 0 0 nil)
      img)))

(defn encode-image
  "Encode a single image into the stream"
  [image encoder]
  (let [good-image (convert-image image BufferedImage/TYPE_3BYTE_BGR)
        packet (IPacket/make)
        converter (ConverterFactory/createConverter good-image IPixelFormat$Type/YUV420P)
        now (System/currentTimeMillis)]
    (when (= @snap-time -1)
      (reset! snap-time now))
    (let [stamp (* (- now @snap-time) 1000)
          outFrame (.toPicture converter good-image stamp)]
      (.setQuality outFrame 0)
      (when (< (.encodeVideo encoder packet outFrame 0) 0)
        (throw (RuntimeException. "Error in encoding")))
      packet)))

(defn new-packet
  "Make a new packet by encoding a new screenshot"
  [encoder]
  (encode-image (take-screenshot) encoder))

(defn get-packet
  "get packet depending on the index in stream"
  [encoder index]
  (if (or (= @reframe nil) (= (mod index 10) 0))
    (reset! reframe (new-packet encoder))
    (let [stamp (+ 1 (.getCurrentDts (.getStream encoder)))]
      ; resend old frames to save perf and reduce latency in stream
      ; must set dts and pts to current + 1 to avoid errors
      (doto @reframe (.setDts stamp) (.setPts stamp) #(.stampOutputPacket (.getStream encoder) %)))))

(defn start-streaming
  [container encoder frameRate out-s]
  (if (< (.open encoder nil nil) 0)
    (throw (RuntimeException. "Failed opening encoder"))
    (if (< (.writeHeader container) 0)
      (throw (RuntimeException. "Failed writing header"))
      (do
        (while (not @exit?)
          (.writePacket container (get-packet encoder @frames))
          (reset! frames (+ @frames 1))
          (when (>= @frames (* 1500 (.getDouble frameRate)))
            (reset! exit? true)))
        (if (< (.writeTrailer container) 0)
          (throw (RuntimeException. "Writing trailer failed"))
          (println "Finish OK"))))))

(defn parseline [line]
  (println (str line "\n"))
  (when (= line nil)
    (reset! exit? true)))

(defn http-reply
  [out]
  (let [writer (PrintWriter. out true)]
    (.println writer "HTTP/1.1 200 OK")
    (.println writer "Content-Type: video/ogg")
    (.println writer "")))

(defn listen-server [port] 
  (with-open [sock (ServerSocket. port)
              client (.accept sock)
              in (BufferedReader. (InputStreamReader. (.getInputStream client)))
              out-stream (.getOutputStream client)
              ]
    (let [container (make-container out-stream) ;(file-container) 
          frameRate (IRational/make 50 1)
          encoder (make-stream container frameRate)]
      (Thread/sleep 1000)
      (start-streaming container encoder frameRate out-stream))))

;(save-image (convert-image (take-screenshot) BufferedImage/TYPE_3BYTE_BGR))
(println "Start")
(listen-server 4444)

;(let [container (file-container) ;container (make-container (.getOutputStream client))
;      frameRate (IRational/make 3 1)
;      encoder (make-stream container frameRate)]
;  (start-streaming container encoder frameRate))
