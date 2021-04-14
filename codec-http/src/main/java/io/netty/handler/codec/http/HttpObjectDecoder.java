/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.AppendableCharSequence;

import java.util.List;

/**
 * Decodes {@link ByteBuf}s into {@link HttpMessage}s and
 * {@link HttpContent}s.
 *
 * <h3>Parameters that prevents excessive memory consumption</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code maxInitialLineLength}</td>
 * <td>The maximum length of the initial line
 *     (e.g. {@code "GET / HTTP/1.0"} or {@code "HTTP/1.0 200 OK"})
 *     If the length of the initial line exceeds this value, a
 *     {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxHeaderSize}</td>
 * <td>The maximum length of all headers.  If the sum of the length of each
 *     header exceeds this value, a {@link TooLongFrameException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxChunkSize}</td>
 * <td>The maximum length of the content or each chunk.  If the content length
 *     (or the length of each chunk) exceeds this value, the content or chunk
 *     will be split into multiple {@link HttpContent}s whose length is
 *     {@code maxChunkSize} at maximum.</td>
 * </tr>
 * </table>
 *
 * <h3>Chunked Content</h3>
 *
 * If the content of an HTTP message is greater than {@code maxChunkSize} or
 * the transfer encoding of the HTTP message is 'chunked', this decoder
 * generates one {@link HttpMessage} instance and its following
 * {@link HttpContent}s per single HTTP message to avoid excessive memory
 * consumption. For example, the following HTTP message:
 * <pre>
 * GET / HTTP/1.1
 * Transfer-Encoding: chunked
 *
 * 1a
 * abcdefghijklmnopqrstuvwxyz
 * 10
 * 1234567890abcdef
 * 0
 * Content-MD5: ...
 * <i>[blank line]</i>
 * </pre>
 * triggers {@link HttpRequestDecoder} to generate 3 objects:
 * <ol>
 * <li>An {@link HttpRequest},</li>
 * <li>The first {@link HttpContent} whose content is {@code 'abcdefghijklmnopqrstuvwxyz'},</li>
 * <li>The second {@link LastHttpContent} whose content is {@code '1234567890abcdef'}, which marks
 * the end of the content.</li>
 * </ol>
 *
 * If you prefer not to handle {@link HttpContent}s by yourself for your
 * convenience, insert {@link HttpObjectAggregator} after this decoder in the
 * {@link ChannelPipeline}.  However, please note that your server might not
 * be as memory efficient as without the aggregator.
 *
 * <h3>Extensibility</h3>
 *
 * Please note that this decoder is designed to be extended to implement
 * a protocol derived from HTTP, such as
 * <a href="http://en.wikipedia.org/wiki/Real_Time_Streaming_Protocol">RTSP</a> and
 * <a href="http://en.wikipedia.org/wiki/Internet_Content_Adaptation_Protocol">ICAP</a>.
 * To implement the decoder of such a derived protocol, extend this class and
 * implement all abstract methods properly.
 */
public abstract class HttpObjectDecoder extends ByteToMessageDecoder {
    private static final String EMPTY_VALUE = "";//请求头空值

    private final int maxChunkSize;//块的最大长度
    private final boolean chunkedSupported;//是否支持分块chunk发送
    protected final boolean validateHeaders;//是否验证头名字合法性
    private final HeaderParser headerParser;//请求头解析器
    private final LineParser lineParser;//换行符解析器

    private HttpMessage message;//请求的消息，包括请求行和请求头
    private long chunkSize;//保存下一次要读的消息体长度
    private long contentLength = Long.MIN_VALUE;//消息体长度
    private volatile boolean resetRequested;//重置请求

    // These will be updated by splitHeader(...)
    private CharSequence name;//头名字
    private CharSequence value;//头的值

    private LastHttpContent trailer;//请求体结尾

    /**
	 * 状态
     * The internal state of {@link HttpObjectDecoder}.
     * <em>Internal use only</em>.
     */
    private enum State {
        SKIP_CONTROL_CHARS,//检查控制字符
        READ_INITIAL,//开始读取
        READ_HEADER,//读取头
        READ_VARIABLE_LENGTH_CONTENT,//读取可变长内容，用于chunk传输
        READ_FIXED_LENGTH_CONTENT,//读取固定长内容 用于Content-Length
        READ_CHUNK_SIZE,//chunk传输的每个chunk尺寸
        READ_CHUNKED_CONTENT,//每个chunk内容
        READ_CHUNK_DELIMITER,//chunk分割
        READ_CHUNK_FOOTER,//最后一个chunk
        BAD_MESSAGE,//无效消息
        UPGRADED//协议切换
    }

	//状态
    private State currentState = State.SKIP_CONTROL_CHARS;

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     */
    //参数对应一行最大长度，请求头的最大长度，请求体或者某个块的最大长度，是否支持chunk块传输
    protected HttpObjectDecoder() {
        this(4096, 8192, 8192, true);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean chunkedSupported) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, true);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean chunkedSupported, boolean validateHeaders) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, chunkedSupported, validateHeaders, 128);
    }

    protected HttpObjectDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean chunkedSupported, boolean validateHeaders, int initialBufferSize) {
        checkPositive(maxInitialLineLength, "maxInitialLineLength");
        checkPositive(maxHeaderSize, "maxHeaderSize");
        checkPositive(maxChunkSize, "maxChunkSize");

        //可添加的字符序列
		//底层是一个字符数组，可以动态添加到最后，具体的源码可以自己看看，因为下面要用，所以我就提一下
        AppendableCharSequence seq = new AppendableCharSequence(initialBufferSize);
        lineParser = new LineParser(seq, maxInitialLineLength);
        //头解析器
        headerParser = new HeaderParser(seq, maxHeaderSize);
        this.maxChunkSize = maxChunkSize;
        this.chunkedSupported = chunkedSupported;
        this.validateHeaders = validateHeaders;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        if (resetRequested) {
            resetNow();
        }

        switch (currentState) {
        //首先我们要检查下我们的字节缓冲区里面是不是全是控制字符(类似回车换行，空格这种)，
		// 如果是的话就不处理，返回了，不是的话就略过控制字符，然后返回。如果不全是控制字符，那就状态切换到READ_INITIAL开始读取
        case SKIP_CONTROL_CHARS: {//检查并略过控制字符SKIP_CONTROL_CHARS
            if (!skipControlCharacters(buffer)) {
                return;
            }
            currentState = State.READ_INITIAL;//切换成 READ_INITIAL 状态，开始读取
        }
        //会开始读取一行，如果没有读到换行符，可能是因为数据还没收全，那就什么都不做，返回。
		// 否则就开始分割，分割出方法，URI，协议，当然如果请求头无效，就不管了，重新返回到SKIP_CONTROL_CHARS状态。
		// 如果是有效的，就封装成请求消息HttpMessage包括请求行和请求头信息，讲状态切换到READ_HEADER读头信息。
        case READ_INITIAL: try {//开始读取 //读取请求行
            AppendableCharSequence line = lineParser.parse(buffer);//解析一行数据
            if (line == null) {//没解析到换行符
                return;
            }
            String[] initialLine = splitInitialLine(line);//行分割后的数组
            if (initialLine.length < 3) {//小于3个就说明格式(方法 URI 版本)不对，直接忽略
                // Invalid initial line - ignore.
                currentState = State.SKIP_CONTROL_CHARS;
                return;
            }

            //创建一个DefaultHttpRequest，就是一个HttpRequest接口的默认实现，封装请求行和请求头信息
            message = createMessage(initialLine);//创建请求消息
            currentState = State.READ_HEADER;
            // fall-through
        } catch (Exception e) {
        	//invalidMessage无效消息
			//创建一个无效消息，状态直接为BAD_MESSAGE无效，把缓冲区内的数据直接都略过，
			// 如果请求消息没创建好，就创建一个，然后设置失败结果并带上异常信息返回。
            out.add(invalidMessage(buffer, e));
            return;
        }
        //首先会先解析请求头，然后看里面有没有transfer-encoding或者content-length，来进行后续的消息体读取。
        case READ_HEADER: try {//读取请求头
            State nextState = readHeaders(buffer);//解析读取请求头
            if (nextState == null) {
                return;
            }
            currentState = nextState;
            switch (nextState) {
            case SKIP_CONTROL_CHARS://没有内容，直接传递两个消息
                // fast-path
                // No content is expected.
                out.add(message);
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);//空内容
                resetNow();
                return;
            case READ_CHUNK_SIZE://块协议传递
                if (!chunkedSupported) {
                    throw new IllegalArgumentException("Chunked messages not supported");
                }
                // Chunked encoding - generate HttpMessage first.  HttpChunks will follow.
                out.add(message);
                return;
            default://没有transfer-encoding或者content-length头 表示没消息体，比如GET请求
                /**
                 * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230, 3.3.3</a> states that if a
                 * request does not have either a transfer-encoding or a content-length header then the message body
                 * length is 0. However for a response the body length is the number of octets received prior to the
                 * server closing the connection. So we treat this as variable length chunked encoding.
                 */
                long contentLength = contentLength();
                if (contentLength == 0 || contentLength == -1 && isDecodingRequest()) {//没消息体，直接就补一个空消息体
                    out.add(message);//消息行和消息头
                    out.add(LastHttpContent.EMPTY_LAST_CONTENT);//空消息体
                    resetNow();//重置属性
                    return;
                }

                assert nextState == State.READ_FIXED_LENGTH_CONTENT ||
                        nextState == State.READ_VARIABLE_LENGTH_CONTENT;
				//有消息体，就先放入行和头信息，下一次解码再进行消息体的读取
                out.add(message);

                if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
                    // chunkSize will be decreased as the READ_FIXED_LENGTH_CONTENT state reads data chunk by chunk.
                    chunkSize = contentLength;//如果是固定长度的消息体，要保存下一次要读的消息体长度
                }

                // We return here, this forces decode to be called again where we will decode the content
                return;
            }
        } catch (Exception e) {
            out.add(invalidMessage(buffer, e));//异常了就无效
            return;
        }
        case READ_VARIABLE_LENGTH_CONTENT: {//读取可变长内容 没有content-length和Transfer-Encoding
            // Keep reading data as a chunk until the end of connection is reached.
            int toRead = Math.min(buffer.readableBytes(), maxChunkSize);
            if (toRead > 0) {
                ByteBuf content = buffer.readRetainedSlice(toRead);
                out.add(new DefaultHttpContent(content));
            }
            return;
        }
        case READ_FIXED_LENGTH_CONTENT: {//读取固定长内容 content-length
            int readLimit = buffer.readableBytes();

            // Check if the buffer is readable first as we use the readable byte count
            // to create the HttpChunk. This is needed as otherwise we may end up with
            // create an HttpChunk instance that contains an empty buffer and so is
            // handled like it is the last HttpChunk.
            //
            // See https://github.com/netty/netty/issues/433
            if (readLimit == 0) {
                return;
            }

            int toRead = Math.min(readLimit, maxChunkSize);//读取的个数
            if (toRead > chunkSize) {//如果大于块长度chunkSize，就读chunkSize个
                toRead = (int) chunkSize;
            }
            ByteBuf content = buffer.readRetainedSlice(toRead);
            chunkSize -= toRead;

            if (chunkSize == 0) {//块全部读完了
                // Read all content.
                out.add(new DefaultLastHttpContent(content, validateHeaders));//创建最后一个内容体，返回
                resetNow();//重置参数
            } else {
                out.add(new DefaultHttpContent(content));//还没读完，就创建一个消息体
            }
            return;
        }
        /**
         * everything else after this point takes care of reading chunked content. basically, read chunk size,
         * read chunk, read and ignore the CRLF and repeat until 0
         */
        //如果读取的块长度是0了，那说明要到最后一个了，状态就要转到READ_CHUNK_FOOTER，否则就转到读内容READ_CHUNKED_CONTENT
        case READ_CHUNK_SIZE: try {//读取块协议大小
            AppendableCharSequence line = lineParser.parse(buffer);
            if (line == null) {
                return;
            }
            int chunkSize = getChunkSize(line.toString());
            this.chunkSize = chunkSize;//块长度
            if (chunkSize == 0) {//读到块结束标记 0\r\n
                currentState = State.READ_CHUNK_FOOTER;
                return;
            }
            currentState = State.READ_CHUNKED_CONTENT;//继续读内容
            // fall-through
        } catch (Exception e) {
            out.add(invalidChunk(buffer, e));//无效块
            return;
        }
        //根据块长度chunkSize读取字节，
		// 如果读取长度等于chunkSize，表示读完了，需要读取分隔符，也就是换车换行了，状态转到READ_CHUNK_DELIMITER，
		// 否则就将读取的内容，封装成DefaultHttpContent传递下去，然后下一次继续读取内容
        case READ_CHUNKED_CONTENT: {//读取块内容，其实没读取，只是用切片，从切片读，不影响原来的
            assert chunkSize <= Integer.MAX_VALUE;
            int toRead = Math.min((int) chunkSize, maxChunkSize);
            toRead = Math.min(toRead, buffer.readableBytes());
            if (toRead == 0) {
                return;
            }
            HttpContent chunk = new DefaultHttpContent(buffer.readRetainedSlice(toRead));//创建一个块，里面放的是切片
            chunkSize -= toRead;

            out.add(chunk);

            if (chunkSize != 0) {//当前块还没接受完，就返回
                return;
            }
            currentState = State.READ_CHUNK_DELIMITER;//接受完，找到块分割符
            // fall-through
        }
        case READ_CHUNK_DELIMITER: {//读取块分隔符，其实就是回车换行符，找到了就转到READ_CHUNK_SIZE继续去取下一个块长度
            final int wIdx = buffer.writerIndex();
            int rIdx = buffer.readerIndex();
            while (wIdx > rIdx) {
                byte next = buffer.getByte(rIdx++);
                if (next == HttpConstants.LF) {//找到换行符，继续读下一个块的大小
                    currentState = State.READ_CHUNK_SIZE;
                    break;
                }
            }
            buffer.readerIndex(rIdx);
            return;
        }
        //如果读取的块长度chunkSize=0的话，就说明是最后一个块了，
		// 然后要看下是否还有头信息在后面，有头信息的话会封装成DefaultLastHttpContent，
		// 如果没有的话头信息就是LastHttpContent.EMPTY_LAST_CONTENT
        case READ_CHUNK_FOOTER: try {//读最后一个块
            LastHttpContent trailer = readTrailingHeaders(buffer);//读取最后的内容，可能有头信息，也可能没有
            if (trailer == null) {//还没结束的，继续
                return;
            }
            out.add(trailer);//添加最后内容
            resetNow();
            return;
        } catch (Exception e) {
            out.add(invalidChunk(buffer, e));
            return;
        }
        case BAD_MESSAGE: {//无效消息
            // Keep discarding until disconnection.
            buffer.skipBytes(buffer.readableBytes());//坏消息，直接略过，不读
            break;
        }
        case UPGRADED: {//协议切换
            int readableBytes = buffer.readableBytes();
            if (readableBytes > 0) {
                // Keep on consuming as otherwise we may trigger an DecoderException,
                // other handler will replace this codec with the upgraded protocol codec to
                // take the traffic over at some point then.
                // See https://github.com/netty/netty/issues/2173
                out.add(buffer.readBytes(readableBytes));
            }
            break;
        }
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decodeLast(ctx, in, out);

        if (resetRequested) {
            // If a reset was requested by decodeLast() we need to do it now otherwise we may produce a
            // LastHttpContent while there was already one.
            resetNow();
        }
        // Handle the last unfinished message.
        if (message != null) {
            boolean chunked = HttpUtil.isTransferEncodingChunked(message);
            if (currentState == State.READ_VARIABLE_LENGTH_CONTENT && !in.isReadable() && !chunked) {
                // End of connection.
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);
                resetNow();
                return;
            }

            if (currentState == State.READ_HEADER) {
                // If we are still in the state of reading headers we need to create a new invalid message that
                // signals that the connection was closed before we received the headers.
                out.add(invalidMessage(Unpooled.EMPTY_BUFFER,
                        new PrematureChannelClosureException("Connection closed before received headers")));
                resetNow();
                return;
            }

            // Check if the closure of the connection signifies the end of the content.
            boolean prematureClosure;
            if (isDecodingRequest() || chunked) {
                // The last request did not wait for a response.
                prematureClosure = true;
            } else {
                // Compare the length of the received content and the 'Content-Length' header.
                // If the 'Content-Length' header is absent, the length of the content is determined by the end of the
                // connection, so it is perfectly fine.
                prematureClosure = contentLength() > 0;
            }

            if (!prematureClosure) {
                out.add(LastHttpContent.EMPTY_LAST_CONTENT);
            }
            resetNow();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HttpExpectationFailedEvent) {
            switch (currentState) {
            case READ_FIXED_LENGTH_CONTENT:
            case READ_VARIABLE_LENGTH_CONTENT:
            case READ_CHUNK_SIZE:
                reset();
                break;
            default:
                break;
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    protected boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.status().code();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
                // One exception: Hixie 76 websocket handshake response
                return !(code == 101 && !res.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT)
                         && res.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true));
            }

            switch (code) {
            case 204: case 304:
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the server switched to a different protocol than HTTP/1.0 or HTTP/1.1, e.g. HTTP/2 or Websocket.
     * Returns false if the upgrade happened in a different layer, e.g. upgrade from HTTP/1.1 to HTTP/1.1 over TLS.
     */
    protected boolean isSwitchingToNonHttp1Protocol(HttpResponse msg) {
        if (msg.status().code() != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            return false;
        }
        String newProtocol = msg.headers().get(HttpHeaderNames.UPGRADE);
        return newProtocol == null ||
                !newProtocol.contains(HttpVersion.HTTP_1_0.text()) &&
                !newProtocol.contains(HttpVersion.HTTP_1_1.text());
    }

    /**
     * Resets the state of the decoder so that it is ready to decode a new message.
     * This method is useful for handling a rejected request with {@code Expect: 100-continue} header.
     */
    public void reset() {
        resetRequested = true;
    }

    //重置属性，每次成功解码操作后都要重新设置属性
    private void resetNow() {
        HttpMessage message = this.message;
        this.message = null;
        name = null;
        value = null;
        contentLength = Long.MIN_VALUE;
        lineParser.reset();
        headerParser.reset();
        trailer = null;
        if (!isDecodingRequest()) {//不是请求解码，如果要升级协议
            HttpResponse res = (HttpResponse) message;
            if (res != null && isSwitchingToNonHttp1Protocol(res)) {
                currentState = State.UPGRADED;
                return;
            }
        }

        resetRequested = false;
        currentState = State.SKIP_CONTROL_CHARS;
    }

    private HttpMessage invalidMessage(ByteBuf in, Exception cause) {
        currentState = State.BAD_MESSAGE;//设置无效数据，这样后面同一个消息的数据都会被略过

        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        in.skipBytes(in.readableBytes());//直接不可读，略过可读数据

        if (message == null) {
            message = createInvalidMessage();//创建完整的请求
        }
        message.setDecoderResult(DecoderResult.failure(cause));//设置失败

        HttpMessage ret = message;
        message = null;
        return ret;
    }

    private HttpContent invalidChunk(ByteBuf in, Exception cause) {
        currentState = State.BAD_MESSAGE;

        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        in.skipBytes(in.readableBytes());

        HttpContent chunk = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER);
        chunk.setDecoderResult(DecoderResult.failure(cause));
        message = null;
        trailer = null;
        return chunk;
    }

    private static boolean skipControlCharacters(ByteBuf buffer) {
        boolean skiped = false;
        final int wIdx = buffer.writerIndex();
        int rIdx = buffer.readerIndex();
        while (wIdx > rIdx) {
            int c = buffer.getUnsignedByte(rIdx++);
            if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
                rIdx--;
                skiped = true;
                break;
            }
        }
        buffer.readerIndex(rIdx);
        return skiped;
    }

    private State readHeaders(ByteBuf buffer) {
        final HttpMessage message = this.message;
        final HttpHeaders headers = message.headers();//获得还未解析的（空的）请求头

        AppendableCharSequence line = headerParser.parse(buffer);//解析请求头
        if (line == null) {
            return null;
        }
        if (line.length() > 0) {
            do {
                char firstChar = line.charAtUnsafe(0);
                if (name != null && (firstChar == ' ' || firstChar == '\t')) {
                    //please do not make one line from below code
                    //as it breaks +XX:OptimizeStringConcat optimization
                    String trimmedLine = line.toString().trim();
                    String valueStr = String.valueOf(value);
                    value = valueStr + ' ' + trimmedLine;
                } else {
                    if (name != null) {
                        headers.add(name, value);//如果名字解析出来表示值也出来了，就添加进去
                    }
                    splitHeader(line);//分割请求头
                }

                line = headerParser.parse(buffer);//继续解析头
                if (line == null) {
                    return null;
                }
            } while (line.length() > 0);
        }

        // Add the last header.
        if (name != null) {//添加最后一个
            headers.add(name, value);
        }

        // reset name and value fields
        name = null;
        value = null;

		//找content-length头信息
        List<String> values = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);
        int contentLengthValuesCount = values.size();//长度头的值的个数

        if (contentLengthValuesCount > 0) {
            // Guard against multiple Content-Length headers as stated in
            // https://tools.ietf.org/html/rfc7230#section-3.3.2:
            //
            // If a message is received that has multiple Content-Length header
            //   fields with field-values consisting of the same decimal value, or a
            //   single Content-Length header field with a field value containing a
            //   list of identical decimal values (e.g., "Content-Length: 42, 42"),
            //   indicating that duplicate Content-Length header fields have been
            //   generated or combined by an upstream message processor, then the
            //   recipient MUST either reject the message as invalid or replace the
            //   duplicated field-values with a single valid Content-Length field
            //   containing that decimal value prior to determining the message body
            //   length or forwarding the message.
            if (contentLengthValuesCount > 1 && message.protocolVersion() == HttpVersion.HTTP_1_1) {
				//如果是HTTP_1_1找到多个Content-Length是不对的，要抛异常
                throw new IllegalArgumentException("Multiple Content-Length headers found");
            }
            contentLength = Long.parseLong(values.get(0));//获取消息体长
        }

        if (isContentAlwaysEmpty(message)) {//空内容
            HttpUtil.setTransferEncodingChunked(message, false);//不开启块传输
            return State.SKIP_CONTROL_CHARS;
        } else if (HttpUtil.isTransferEncodingChunked(message)) {
            // See https://tools.ietf.org/html/rfc7230#section-3.3.3
            //
            //       If a message is received with both a Transfer-Encoding and a
            //       Content-Length header field, the Transfer-Encoding overrides the
            //       Content-Length.  Such a message might indicate an attempt to
            //       perform request smuggling (Section 9.5) or response splitting
            //       (Section 9.4) and ought to be handled as an error.  A sender MUST
            //       remove the received Content-Length field prior to forwarding such
            //       a message downstream.
            //
            // This is also what http_parser does:
            // https://github.com/nodejs/http-parser/blob/v2.9.2/http_parser.c#L1769
            if (contentLengthValuesCount > 0 && message.protocolVersion() == HttpVersion.HTTP_1_1) {
				//HTTP_1_1如果开启了块协议，就不能设置Content-Length了
                throw new IllegalArgumentException(
                        "Both 'Content-Length: " + contentLength + "' and 'Transfer-Encoding: chunked' found");
            }

            return State.READ_CHUNK_SIZE;//块传输，要获取大小
        } else if (contentLength() >= 0) {
            return State.READ_FIXED_LENGTH_CONTENT;//可以固定长度解析消息体
        } else {
            return State.READ_VARIABLE_LENGTH_CONTENT;//可变长度解析，或者没有Content-Length，http1.0以及之前或者1.1 非keep alive,Content-Length可有可无
        }
    }

    private long contentLength() {
        if (contentLength == Long.MIN_VALUE) {
            contentLength = HttpUtil.getContentLength(message, -1L);
        }
        return contentLength;
    }

    //读取最后的头信息

	/**
	 * 会去读取一行，如果没读出来换行，表示可能没收到数据，也就是没读完，那就返回，继续下一次。
	 * 如果读出来发现就只有回车换行，那就说明没有头信息，结束了，就返回一个 LastHttpContent.EMPTY_LAST_CONTENT，
	 * 否则的话就创建一个DefaultLastHttpContent内容，然后进行头信息的解析，解析出来的头信息就放入内容中，并返回内容
	 */
    private LastHttpContent readTrailingHeaders(ByteBuf buffer) {
        AppendableCharSequence line = headerParser.parse(buffer);
        if (line == null) {//没有换行，表示没读完呢
            return null;
        }
        LastHttpContent trailer = this.trailer;
        if (line.length() == 0 && trailer == null) {//直接读到\r\n 即读到空行，表示结束,无头信息，返回空内容
            // We have received the empty line which signals the trailer is complete and did not parse any trailers
            // before. Just return an empty last content to reduce allocations.
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }

        CharSequence lastHeader = null;
        if (trailer == null) {
            trailer = this.trailer = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, validateHeaders);//空内容
        }
        while (line.length() > 0) {//chunk最后可能还有头信息 key: 1\r\n
            char firstChar = line.charAtUnsafe(0);
            if (lastHeader != null && (firstChar == ' ' || firstChar == '\t')) {
                List<String> current = trailer.trailingHeaders().getAll(lastHeader);
                if (!current.isEmpty()) {
                    int lastPos = current.size() - 1;
                    //please do not make one line from below code
                    //as it breaks +XX:OptimizeStringConcat optimization
                    String lineTrimmed = line.toString().trim();
                    String currentLastPos = current.get(lastPos);
                    current.set(lastPos, currentLastPos + lineTrimmed);
                }
            } else {//解析头信息
                splitHeader(line);
                CharSequence headerName = name;
                if (!HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRAILER.contentEqualsIgnoreCase(headerName)) {
                    trailer.trailingHeaders().add(headerName, value);
                }
                lastHeader = name;
                // reset name and value fields
                name = null;
                value = null;
            }
            line = headerParser.parse(buffer);
            if (line == null) {
                return null;
            }
        }

        this.trailer = null;
        return trailer;
    }

    protected abstract boolean isDecodingRequest();
    protected abstract HttpMessage createMessage(String[] initialLine) throws Exception;
    protected abstract HttpMessage createInvalidMessage();

    private static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i ++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    //按空格进行一行的分割
    private static String[] splitInitialLine(AppendableCharSequence sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);//找出不是空格的第一个索引
        aEnd = findWhitespace(sb, aStart);//找出空格索引

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[] {
                sb.subStringUnsafe(aStart, aEnd),
                sb.subStringUnsafe(bStart, bEnd),
                cStart < cEnd? sb.subStringUnsafe(cStart, cEnd) : "" };
    }

    private void splitHeader(AppendableCharSequence sb) {
        final int length = sb.length();
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        nameStart = findNonWhitespace(sb, 0);
        for (nameEnd = nameStart; nameEnd < length; nameEnd ++) {
            char ch = sb.charAtUnsafe(nameEnd);
            // https://tools.ietf.org/html/rfc7230#section-3.2.4
            //
            // No whitespace is allowed between the header field-name and colon. In
            // the past, differences in the handling of such whitespace have led to
            // security vulnerabilities in request routing and response handling. A
            // server MUST reject any received request message that contains
            // whitespace between a header field-name and colon with a response code
            // of 400 (Bad Request). A proxy MUST remove any such whitespace from a
            // response message before forwarding the message downstream.
            if (ch == ':' ||
                    // In case of decoding a request we will just continue processing and header validation
                    // is done in the DefaultHttpHeaders implementation.
                    //
                    // In the case of decoding a response we will "skip" the whitespace.
                    (!isDecodingRequest() && Character.isWhitespace(ch))) {
                break;
            }
        }

        if (nameEnd == length) {
            // There was no colon present at all.
            throw new IllegalArgumentException("No colon found");
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd ++) {
            if (sb.charAtUnsafe(colonEnd) == ':') {
                colonEnd ++;
                break;
            }
        }

        name = sb.subStringUnsafe(nameStart, nameEnd);
        valueStart = findNonWhitespace(sb, colonEnd);
        if (valueStart == length) {
            value = EMPTY_VALUE;
        } else {
            valueEnd = findEndOfString(sb);
            value = sb.subStringUnsafe(valueStart, valueEnd);
        }
    }

    private static int findNonWhitespace(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    private static int findWhitespace(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    private static int findEndOfString(AppendableCharSequence sb) {
        for (int result = sb.length() - 1; result > 0; --result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result + 1;
            }
        }
        return 0;
    }

    private static class HeaderParser implements ByteProcessor {
        private final AppendableCharSequence seq;//可添加的字符序列
        private final int maxLength;//最大长度
        private int size;//索引

        HeaderParser(AppendableCharSequence seq, int maxLength) {
            this.seq = seq;
            this.maxLength = maxLength;
        }

		//解析缓冲区
        public AppendableCharSequence parse(ByteBuf buffer) {
            final int oldSize = size;
            seq.reset();
            int i = buffer.forEachByte(this);
            if (i == -1) {//没读到换行，或者报异常了
                size = oldSize;
                return null;
            }
            buffer.readerIndex(i + 1);
            return seq;
        }

		//读到的字符个数清零
        public void reset() {
            size = 0;
        }

		//处理数据，遇到换行了就结束
        @Override
        public boolean process(byte value) throws Exception {
            char nextByte = (char) (value & 0xFF);
            if (nextByte == HttpConstants.CR) {//遇到回车符，直接返回true，不添加字符
                return true;
            }
            if (nextByte == HttpConstants.LF) {//遇到换行符，就会结束
                return false;
            }

            if (++ size > maxLength) {//溢出了
                // TODO: Respond with Bad Request and discard the traffic
                //    or close the connection.
                //       No need to notify the upstream handlers - just log.
                //       If decoding a response, just throw an exception.
                throw newException(maxLength);
            }

            seq.append(nextByte);//添加
            return true;
        }

		//头过大
        protected TooLongFrameException newException(int maxLength) {
            return new TooLongFrameException("HTTP header is larger than " + maxLength + " bytes.");
        }
    }

    //行解析器
	//继承了头解析器，只是解析的时候要reset一下，就是把读到的个数清0，因为是一行行读，每次读完一行就得清理个数。
	// 虽然字符串序列可以不处理，可以复用。
    private static final class LineParser extends HeaderParser {

        LineParser(AppendableCharSequence seq, int maxLength) {
            super(seq, maxLength);
        }

        @Override
        public AppendableCharSequence parse(ByteBuf buffer) {
            reset();//从头开始，要重置索引
            return super.parse(buffer);
        }

        @Override
        protected TooLongFrameException newException(int maxLength) {
            return new TooLongFrameException("An HTTP line is larger than " + maxLength + " bytes.");
        }
    }
}
