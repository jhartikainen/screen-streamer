# Clojure code for streaming desktop screenshots as Theora video

This is a prototype app which takes screenshots of the screen and encodes them into an Ogg Theora video stream.

The code is only tested on OS X. Might work, or it might not.

# Usage

You need Xuggler 5.5 (which is, as of writing this, not released so you need to compile it yourself) in order for this to work at all. There appears to be a bug in Xuggler 5.4 which crashes the application with streaming.

This could be used as a source of HTML5 video, using code like below to show the video:

    <video src="http://localhost:4444"></video>

Assuming application is running on localhost.
