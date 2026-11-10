package io.plainround.seatwave.dns.message;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns DNS messages into bytes and bytes into messages, following RFC 1035 section 4.
 *
 * <p>Decoding is the trust boundary of this project: every packet that arrives is checked before
 * anything else reads it. The header must be complete; the four counts must fit in the packet; a
 * name must be at most 255 octets and every label at most 63; a compression pointer must point
 * strictly before the place the name started, and every further pointer strictly before the last
 * one, so a chain of pointers can never loop; each record's data must have the length its type
 * needs; and no byte may follow the last record. Records of types this lab does not serve (for
 * example the EDNS option record that dig adds) are read and skipped.
 *
 * <p>Encoding writes names without compression, which is simpler and always valid. Stateless and
 * thread-safe.
 */
public final class MessageCodec {

  /** The fixed header: identifier, flags and four counts of two octets each. */
  public static final int HEADER_OCTETS = 12;

  // A question is at least a one-octet root name plus type and class; a record at least the
  // root name plus type, class, TTL and data length (RFC 1035 sections 4.1.2 and 4.1.3).
  private static final int MIN_QUESTION_OCTETS = 5;
  private static final int MIN_RECORD_OCTETS = 11;
  private static final int POINTER_BITS = 0xC0;
  private static final int POINTER_OFFSET_MASK = 0x3F;
  private static final int BYTE_MASK = 0xFF;
  private static final int BITS_PER_BYTE = 8;
  private static final int IPV4_OCTETS = 4;
  private static final int IPV6_OCTETS = 16;
  // The five 32-bit numbers at the end of an SOA record.
  private static final int SOA_NUMBER_OCTETS = 20;
  private static final long UNSIGNED_32_MASK = 0xFFFF_FFFFL;

  private MessageCodec() {}

  /**
   * Reads a packet.
   *
   * @param packet the bytes as they arrived
   * @return the message, or the first problem found
   */
  public static Result<Message, MessageProblem> decode(byte[] packet) {
    Objects.requireNonNull(packet, "packet");
    try {
      return Result.success(new Decoder(packet).message());
    } catch (Malformed malformed) {
      return Result.error(malformed.problem);
    }
  }

  /**
   * Writes a message.
   *
   * @param message the message
   * @return its bytes on the wire
   */
  public static byte[] encode(Message message) {
    Objects.requireNonNull(message, "message");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeShort(out, message.id());
    writeShort(out, message.flags());
    writeShort(out, message.questions().size());
    writeShort(out, message.answers().size());
    writeShort(out, message.authority().size());
    writeShort(out, message.additional().size());
    for (Question question : message.questions()) {
      writeName(out, question.name());
      writeShort(out, question.typeCode());
      writeShort(out, question.classCode());
    }
    for (List<ResourceRecord> section :
        List.of(message.answers(), message.authority(), message.additional())) {
      section.forEach(record -> writeRecord(out, record));
    }
    return out.toByteArray();
  }

  /**
   * Reads the identifier from the first two octets, so a server can answer even a message it cannot
   * otherwise read.
   *
   * @param packet the bytes, at least two long
   * @return the identifier
   */
  public static int idOf(byte[] packet) {
    return ((packet[0] & BYTE_MASK) << BITS_PER_BYTE) | (packet[1] & BYTE_MASK);
  }

  private static void writeRecord(ByteArrayOutputStream out, ResourceRecord record) {
    writeName(out, record.owner());
    writeShort(out, record.type().code());
    writeShort(out, Question.CLASS_INTERNET);
    writeInt(out, record.ttlSeconds());
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    switch (record.data()) {
      case RecordData.Address(var address) -> data.writeBytes(address.getAddress());
      case RecordData.Host(var target) -> writeName(data, target);
      case RecordData.Authority authority -> {
        writeName(data, authority.primaryServer());
        writeName(data, authority.responsibleMailbox());
        for (long number :
            List.of(
                authority.serial(),
                authority.refreshSeconds(),
                authority.retrySeconds(),
                authority.expireSeconds(),
                authority.minimumSeconds())) {
          writeInt(data, number);
        }
      }
      case RecordData.Text(var strings) -> {
        for (String text : strings) {
          byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
          data.write(bytes.length);
          data.writeBytes(bytes);
        }
      }
    }
    writeShort(out, data.size());
    out.writeBytes(data.toByteArray());
  }

  private static void writeName(ByteArrayOutputStream out, DomainName name) {
    for (String label : name.labels()) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.write(bytes.length);
      out.writeBytes(bytes);
    }
    out.write(0);
  }

  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write((value >> BITS_PER_BYTE) & BYTE_MASK);
    out.write(value & BYTE_MASK);
  }

  private static void writeInt(ByteArrayOutputStream out, long value) {
    writeShort(out, (int) ((value >> (2 * BITS_PER_BYTE)) & 0xFFFF));
    writeShort(out, (int) (value & 0xFFFF));
  }

  /**
   * Stops decoding at the first problem. It never leaves this class: {@link #decode} turns it into
   * a {@link Result}, so callers see an expected failure as a value.
   */
  private static final class Malformed extends Exception {
    private static final long serialVersionUID = 1L;

    // Not serialized: the exception never leaves this class.
    private final transient MessageProblem problem;

    Malformed(MessageProblem problem) {
      super(problem.describe(), null, false, false);
      this.problem = problem;
    }
  }

  /** Reads one packet from front to back. Confined to one call of {@link #decode}. */
  private static final class Decoder {
    private final byte[] packet;
    private int position;

    Decoder(byte[] packet) {
      this.packet = packet;
    }

    Message message() throws Malformed {
      if (packet.length < HEADER_OCTETS) {
        throw new Malformed(new MessageProblem.Truncated("header"));
      }
      int id = readShort("header");
      int flags = readShort("header");
      int questionCount = readShort("header");
      int answerCount = readShort("header");
      int authorityCount = readShort("header");
      int additionalCount = readShort("header");
      long recordCount = (long) answerCount + authorityCount + additionalCount;
      // Checked before any list is filled, so a header that claims 65,535 records cannot make
      // the decoder do 65,535 rounds of work on a 12-byte packet.
      if (questionCount * (long) MIN_QUESTION_OCTETS + recordCount * MIN_RECORD_OCTETS
          > packet.length - HEADER_OCTETS) {
        throw new Malformed(
            new MessageProblem.BadCounts("the header announces more than the packet holds"));
      }
      List<Question> questions = new ArrayList<>();
      for (int questionIndex = 0; questionIndex < questionCount; questionIndex++) {
        questions.add(new Question(readName(), readShort("question"), readShort("question")));
      }
      List<ResourceRecord> answers = readRecords(answerCount);
      List<ResourceRecord> authority = readRecords(authorityCount);
      List<ResourceRecord> additional = readRecords(additionalCount);
      if (position != packet.length) {
        throw new Malformed(new MessageProblem.TrailingBytes(packet.length - position));
      }
      return new Message(id, flags, questions, answers, authority, additional);
    }

    private List<ResourceRecord> readRecords(int count) throws Malformed {
      List<ResourceRecord> records = new ArrayList<>();
      for (int recordIndex = 0; recordIndex < count; recordIndex++) {
        readRecord().ifPresent(records::add);
      }
      return records;
    }

    private Optional<ResourceRecord> readRecord() throws Malformed {
      DomainName owner = readName();
      int typeCode = readShort("record");
      int classCode = readShort("record");
      long ttl = readInt("record");
      int dataLength = readShort("record");
      int end = position + dataLength;
      if (end > packet.length) {
        throw new Malformed(new MessageProblem.Truncated("record data"));
      }
      Optional<RecordType> type = RecordType.fromCode(typeCode);
      if (type.isEmpty() || classCode != Question.CLASS_INTERNET) {
        position = end;
        return Optional.empty();
      }
      // A TTL with the top bit set is read as zero (RFC 2181 section 8).
      long ttlSeconds = ttl > ResourceRecord.MAX_TTL_SECONDS ? 0 : ttl;
      RecordData data = readData(type.get(), end);
      if (position != end) {
        throw new Malformed(
            new MessageProblem.BadRecord(type.get() + " data length does not match its content"));
      }
      return Optional.of(new ResourceRecord(owner, type.get(), ttlSeconds, data));
    }

    private RecordData readData(RecordType type, int end) throws Malformed {
      return switch (type) {
        case A -> new RecordData.Address(readAddress(IPV4_OCTETS, end));
        case AAAA -> new RecordData.Address(readAddress(IPV6_OCTETS, end));
        case NS, CNAME -> new RecordData.Host(readName());
        case SOA -> readAuthority(end);
        case TXT -> readText(end);
      };
    }

    private InetAddress readAddress(int octets, int end) throws Malformed {
      if (end - position != octets) {
        throw new Malformed(
            new MessageProblem.BadRecord("an address record must hold " + octets + " octets"));
      }
      byte[] address = new byte[octets];
      System.arraycopy(packet, position, address, 0, octets);
      position = end;
      try {
        // getByAddress never looks anything up; it only checks the length, checked above.
        return InetAddress.getByAddress(address);
      } catch (UnknownHostException wrongLength) {
        throw new Malformed(new MessageProblem.BadRecord(wrongLength.getMessage()));
      }
    }

    private RecordData readAuthority(int end) throws Malformed {
      DomainName primary = readName();
      DomainName mailbox = readName();
      if (end - position != SOA_NUMBER_OCTETS) {
        throw new Malformed(new MessageProblem.BadRecord("an SOA record must end in 20 octets"));
      }
      return new RecordData.Authority(
          primary,
          mailbox,
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"));
    }

    private RecordData readText(int end) throws Malformed {
      List<String> strings = new ArrayList<>();
      while (position < end) {
        int length = packet[position] & BYTE_MASK;
        if (position + 1 + length > end) {
          throw new Malformed(new MessageProblem.BadRecord("a TXT string runs past its record"));
        }
        strings.add(new String(packet, position + 1, length, StandardCharsets.UTF_8));
        position += 1 + length;
      }
      if (strings.isEmpty()) {
        throw new Malformed(new MessageProblem.BadRecord("a TXT record needs a string"));
      }
      return new RecordData.Text(strings);
    }

    private DomainName readName() throws Malformed {
      List<String> labels = new ArrayList<>();
      int cursor = position;
      // Every pointer must point strictly before this mark, and the mark moves back with each
      // jump, so a chain of pointers always ends: no packet can make this loop forever.
      int pointerLimit = position;
      boolean hasJumped = false;
      int wireLength = 1;
      while (true) {
        int length = byteAt(cursor);
        if ((length & POINTER_BITS) == POINTER_BITS) {
          int target = ((length & POINTER_OFFSET_MASK) << BITS_PER_BYTE) | byteAt(cursor + 1);
          if (target >= pointerLimit) {
            throw new Malformed(
                new MessageProblem.BadName("a compression pointer must point backward"));
          }
          if (!hasJumped) {
            position = cursor + 2;
            hasJumped = true;
          }
          pointerLimit = target;
          cursor = target;
          continue;
        }
        if ((length & POINTER_BITS) != 0) {
          throw new Malformed(new MessageProblem.BadName("a reserved label type"));
        }
        if (length == 0) {
          if (!hasJumped) {
            position = cursor + 1;
          }
          break;
        }
        wireLength += 1 + length;
        if (wireLength > DomainName.MAX_NAME_OCTETS) {
          throw new Malformed(new MessageProblem.BadName("longer than 255 octets"));
        }
        if (cursor + 1 + length > packet.length) {
          throw new Malformed(new MessageProblem.Truncated("name"));
        }
        labels.add(
            new String(packet, cursor + 1, length, StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT));
        cursor += 1 + length;
      }
      String problem = DomainName.problemWith(labels);
      if (!problem.isEmpty()) {
        throw new Malformed(new MessageProblem.BadName(problem));
      }
      return new DomainName(labels);
    }

    private int byteAt(int index) throws Malformed {
      if (index >= packet.length) {
        throw new Malformed(new MessageProblem.Truncated("name"));
      }
      return packet[index] & BYTE_MASK;
    }

    private int readShort(String part) throws Malformed {
      if (position + 2 > packet.length) {
        throw new Malformed(new MessageProblem.Truncated(part));
      }
      int value =
          ((packet[position] & BYTE_MASK) << BITS_PER_BYTE) | (packet[position + 1] & BYTE_MASK);
      position += 2;
      return value;
    }

    private long readInt(String part) throws Malformed {
      long high = readShort(part);
      long low = readShort(part);
      return ((high << (2 * BITS_PER_BYTE)) | low) & UNSIGNED_32_MASK;
    }
  }
}
