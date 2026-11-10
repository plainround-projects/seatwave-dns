package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.zone.Lookup;
import io.plainround.seatwave.dns.zone.Zone;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Turns one query packet into one response packet, from one zone. It holds no state and touches no
 * socket, so every rule of the server is tested here without a network.
 *
 * <p>The rules: a packet shorter than a header, or one that is itself a response, gets no answer at
 * all (answering responses lets two servers bounce packets forever); a packet that cannot be read
 * gets FORMERR; an opcode other than a standard query gets NOTIMP; a query must carry exactly one
 * question (RFC 9619); a name outside the zone gets REFUSED; otherwise the zone answers, refers to
 * a child zone, says "no data" or says NXDOMAIN, with the SOA in the authority section so the "no"
 * can be cached for the shorter of the SOA's TTL and its minimum field (RFC 2308 section 3). A
 * response longer than 512 octets is cut to its header and question with the truncated flag set
 * (RFC 1035 section 4.2.1). Thread-safe if each thread brings its own random generator.
 */
public final class Responder {

  /** The largest UDP message without EDNS (RFC 1035 section 4.2.1). */
  public static final int MAX_UDP_OCTETS = 512;

  // The opcode bits and the recursion-desired bit are copied from the query (RFC 1035 4.1.1).
  private static final int COPIED_FLAGS = 0x7800 | Message.RECURSION_DESIRED_FLAG;
  private static final int FLAGS_OFFSET = 2;
  private static final int BYTE_MASK = 0xFF;
  private static final int BITS_PER_BYTE = 8;

  private Responder() {}

  /**
   * Answers one packet.
   *
   * @param packet the query as it arrived
   * @param zone the zone to answer from
   * @param random picks the first address of a weighted name
   * @return the response, or empty if the packet must not be answered
   */
  public static Optional<byte[]> respond(byte[] packet, Zone zone, RandomGenerator random) {
    Objects.requireNonNull(packet, "packet");
    Objects.requireNonNull(zone, "zone");
    if (packet.length < MessageCodec.HEADER_OCTETS || isResponse(packet)) {
      return Optional.empty();
    }
    int queryFlags =
        ((packet[FLAGS_OFFSET] & BYTE_MASK) << BITS_PER_BYTE)
            | (packet[FLAGS_OFFSET + 1] & BYTE_MASK);
    Message response =
        switch (MessageCodec.decode(packet)) {
          case Result.Success<Message, MessageProblem>(Message query) ->
              answer(query, zone, random);
          case Result.Error<Message, MessageProblem> unreadable ->
              reply(MessageCodec.idOf(packet), queryFlags, List.of(), ResponseCode.FORMAT_ERROR);
        };
    byte[] encoded = MessageCodec.encode(response);
    if (encoded.length > MAX_UDP_OCTETS) {
      Message cut =
          new Message(
              response.id(),
              response.flags() | Message.TRUNCATED_FLAG,
              response.questions(),
              List.of(),
              List.of(),
              List.of());
      return Optional.of(MessageCodec.encode(cut));
    }
    return Optional.of(encoded);
  }

  private static Message answer(Message query, Zone zone, RandomGenerator random) {
    if (query.opcode() != 0) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.NOT_IMPLEMENTED);
    }
    if (query.questions().size() != 1) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.FORMAT_ERROR);
    }
    Question question = query.questions().getFirst();
    if (question.classCode() != Question.CLASS_INTERNET) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.REFUSED);
    }
    int answerFlags = flags(query.flags(), ResponseCode.NO_ERROR) | Message.AUTHORITATIVE_FLAG;
    return switch (zone.lookup(question, random)) {
      case Lookup.Answer(var records) ->
          new Message(query.id(), answerFlags, query.questions(), records, List.of(), List.of());
      case Lookup.Referral(var nameServers, var glue) ->
          new Message(
              query.id(),
              flags(query.flags(), ResponseCode.NO_ERROR),
              query.questions(),
              List.of(),
              nameServers,
              glue);
      case Lookup.NoData(var soa) ->
          new Message(
              query.id(),
              answerFlags,
              query.questions(),
              List.of(),
              List.of(negative(soa)),
              List.of());
      case Lookup.NameError(var soa) ->
          new Message(
              query.id(),
              flags(query.flags(), ResponseCode.NAME_ERROR) | Message.AUTHORITATIVE_FLAG,
              query.questions(),
              List.of(),
              List.of(negative(soa)),
              List.of());
      case Lookup.OutOfZone() ->
          reply(query.id(), query.flags(), query.questions(), ResponseCode.REFUSED);
    };
  }

  private static ResourceRecord negative(ResourceRecord soa) {
    long minimum = ((RecordData.Authority) soa.data()).minimumSeconds();
    return soa.withTtl(Math.min(soa.ttlSeconds(), minimum));
  }

  private static Message reply(
      int id, int queryFlags, List<Question> questions, ResponseCode code) {
    return new Message(id, flags(queryFlags, code), questions, List.of(), List.of(), List.of());
  }

  private static int flags(int queryFlags, ResponseCode code) {
    return Message.RESPONSE_FLAG | (queryFlags & COPIED_FLAGS) | code.code();
  }

  private static boolean isResponse(byte[] packet) {
    return ((packet[FLAGS_OFFSET] & BYTE_MASK) << BITS_PER_BYTE & Message.RESPONSE_FLAG) != 0;
  }
}
