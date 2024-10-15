package sittingduck;

import sittingduck.fast.FastQueue;
import battlecode.common.*;

public class Comms {

    private static RobotController rc;

    private static int[] bufferPool;
    private static boolean[] dirtyFlags;

    //ALLOCATIONS 
    // Slots 1-4:
    // S1 --> symmetry (3), flags captured (2) 
    //      

    // Slots 5-24:
    // Squadron updates (move, attack, retreat, protect, build, stuck, etc.)

    // Slots 25-44:
    // Sector updates (danger level, pathing blockage, etc.)
    //first two bits: 00 --> flag stolen
    //consider round info as recent --> will only be tens and ones digit of current rounds
    //100 turns --> 5 turn buckets --> 20 --> 4 bits

    //slot 63, 62, 61: builders



    static FastQueue<Integer> sectorMessages = new FastQueue<Integer>(100);
    static FastQueue<Integer> sectorMessageQueue = new FastQueue<Integer>(25);


    public static int encodeRound() {
        return (rc.getRoundNum()%80)/5;
    }

    public static SectorInfo decodeSectorMessage(int packet) {
        int round = ((packet & 0x3c) >> 2) * 5;
        int thisRound = rc.getRoundNum();
        if (thisRound % 100 < round) {
            round += (thisRound - thisRound % 80) - 80;
        }
        else {
            round += (thisRound - thisRound % 80);
        }
        MapLocation packetLoc = sectorToLocation((packet & 0x3fc0) >> 6);
        SectorInfo res = new SectorInfo(packetLoc, round);
        
        switch (packet & 0x3) {
            case 0x0: //distress flag packet
                res.type = 0;
                res.flagID = packet >> 14;
                break;
            case 0x1:
                res.type = 1;
                break;
            default:
                break;
        }
        return res;
    }

    public static void flushSectorMessageQueue() throws GameActionException {
        while (!sectorMessageQueue.isEmpty()) {
            if (Clock.getBytecodesLeft() < 2000) break;
            int m = sectorMessageQueue.poll();
            writeNextSectorPacket(m);
        }
    }

    public static void distressFlag(int flagnum, MapLocation m) throws GameActionException {
        //send a sector packet
        int packet = 0x0 | (encodeRound() << 2) | (locationToSector(m) << 6) | (flagnum << 14);
        // 2 (id) + 4 (round) + 6 (loc) + 2 (flagid) = 16 bits
        sectorMessageQueue.add(packet);
        //System.out.println("queueing flag packet @ " + m + " " + rc.getRoundNum());
        //SectorInfo decoded = decodeSectorMessage(packet);
        // System.out.println(decoded.loc.toString());
        // System.out.println(decoded.round + " ");
        // System.out.println(flagnum + " " + decoded.flagID);
        // System.out.println(packet);
        // System.out.println((encodeRound() << 2));
        // System.out.println((locationToSector(m) << 6));
    }

    public static MapLocation[] getBuilderLocations() {
        MapLocation[] ret = {null, null, null};
        if (read(63) != 42069) {
            ret[0] = sectorToLocation(read(63));
        }
        if (read(62) != 42069) {
            ret[1] = sectorToLocation(read(62));
        }
        if (read(61) != 42069) {
            ret[2] = sectorToLocation(read(61));
        }
        return ret;
    }

    public static void writeBuilderLocation(MapLocation m, int myMoveNumber) throws GameActionException {
        if (myMoveNumber == 1) {
            if (m == null) {
                writeToBufferPool(63, 42069);
            }
            else {
                writeToBufferPool(63, locationToSector(m));
            }
        }
        else if (myMoveNumber == 11) {
            if (m == null) {
                writeToBufferPool(62, 42069);
            }
            else {
                writeToBufferPool(62, locationToSector(m));
            }
        }
        else if (myMoveNumber == 21) {
            if (m == null) {
                writeToBufferPool(61, 42069);
            }
            else {
                writeToBufferPool(61, locationToSector(m));
            }
        }
    }




    public static void notifyCombatFronts(MapLocation m) throws GameActionException {
        int packet = 0x1 | (encodeRound() << 2) | (locationToSector(m) << 6);
        // 2 (id) + 4 (round) + 6 (loc) + 2 (flagid) = 14 bits
        sectorMessageQueue.add(packet);
        // System.out.println("queueing combaqt packet @ " + m + " " + rc.getRoundNum());
        // SectorInfo decoded = decodeSectorMessage(packet);
        // System.out.println(decoded.loc.toString());
        // System.out.println(decoded.round + " ");
    }

    public static int readSymmetry() { //0 bit = symm could be valid, 1 = invalid
        if (!isBitSet(0, 2)) return macroPath.R_SYM;
        if (!isBitSet(0, 0)) return macroPath.H_SYM;
        if (!isBitSet(0, 1)) return macroPath.V_SYM;
        //this should never happen
        return -1;
    }

    public static int locationToSector(MapLocation m) {
        //cast 4 --> 1
        return ((m.x/4) << 4) | (m.y/4);
    }

    public static MapLocation sectorToLocation(int sector) {
        return new MapLocation((sector >> 4) * 4 + 1, (sector & 0x7) * 4 + 1);
    }

    public static boolean isSymmetry(int symm) {
        if(isBitSet(0, symm)) return false;
        return true;
    }

    public static void invalidateSymmetry(int symm) throws GameActionException {
        writeToBufferPool(0, bufferPool[0] | (1<<symm));
    }

    public static int countFlagsCaptured() { //can do this more efficiently later but what
        return (read(0) & 0x18) >> 3;
    }

    public static void depositFlag() throws GameActionException {
        int ans = countFlagsCaptured();
        ans++;
        int mask = read(0); 
        mask = (mask & 0xffffffe7) | (ans << 3);
        Debug.println(mask + " " + ans);
        writeToBufferPool(0, mask);
        Debug.println(countFlagsCaptured() + " ");
    }

    final static int[] troopBuckets = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    public static boolean myFlagExists(int flagnum) {
        return isBitSet(flagnum, 5);
    }

    public static void writeMyFlag(int flagnum, int exists) throws GameActionException {
        int mask = (read(flagnum) & 0xffdf) | (exists << 5);
        writeToBufferPool(flagnum, mask);
    }

    public static void writeFlagStatus(int flagnum, int counts) throws GameActionException { 
        //bits: 4 bits each
        int basemask = 0xf; //<< (4 * flagnum);
        int mask = (read(1 + flagnum) & (0xffff ^ basemask)) | (counts); //<< (4 * flagnum)
        writeToBufferPool(1 + flagnum, mask);
    }

    public static int readFlagStatus(int flagnum) { //returns true if theres more enemy ducks at flag then friendly ducks AND that flag still exists
        int basemask = 0xf; // << (4 * flagnum);
        int mask = (read(1 + flagnum) & basemask);
        return mask;
    }

    public static void init(RobotController r) {
        rc = r;
        bufferPool = new int[64];
        dirtyFlags = new boolean[64];
    }

    public static int read(int idx) {
        return bufferPool[idx];
    }

    public static boolean isBitSet(int idx, int pos) {
        return ((bufferPool[idx] & (1 << pos)) != 0);
    }

    public static void writeToBufferPool(int idx, int value) throws GameActionException {
        bufferPool[idx] = value;
        dirtyFlags[idx] = true;
    }

    public static void initBufferPool() throws GameActionException {
        dirtyFlags = new boolean[64];

        bufferPool[0] = rc.readSharedArray(0);
        bufferPool[1] = rc.readSharedArray(1);
        bufferPool[2] = rc.readSharedArray(2);
        bufferPool[3] = rc.readSharedArray(3);
        bufferPool[4] = rc.readSharedArray(4);
        bufferPool[5] = rc.readSharedArray(5);
        bufferPool[6] = rc.readSharedArray(6);
        bufferPool[7] = rc.readSharedArray(7);
        bufferPool[8] = rc.readSharedArray(8);
        bufferPool[9] = rc.readSharedArray(9);
        bufferPool[10] = rc.readSharedArray(10);
        bufferPool[11] = rc.readSharedArray(11);
        bufferPool[12] = rc.readSharedArray(12);
        bufferPool[13] = rc.readSharedArray(13);
        bufferPool[14] = rc.readSharedArray(14);
        bufferPool[15] = rc.readSharedArray(15);
        bufferPool[16] = rc.readSharedArray(16);
        bufferPool[17] = rc.readSharedArray(17);
        bufferPool[18] = rc.readSharedArray(18);
        bufferPool[19] = rc.readSharedArray(19);
        bufferPool[20] = rc.readSharedArray(20);
        bufferPool[21] = rc.readSharedArray(21);
        bufferPool[22] = rc.readSharedArray(22);
        bufferPool[23] = rc.readSharedArray(23);
        bufferPool[24] = rc.readSharedArray(24);
        bufferPool[25] = rc.readSharedArray(25);
        bufferPool[26] = rc.readSharedArray(26);
        bufferPool[27] = rc.readSharedArray(27);
        bufferPool[28] = rc.readSharedArray(28);
        bufferPool[29] = rc.readSharedArray(29);
        bufferPool[30] = rc.readSharedArray(30);
        bufferPool[31] = rc.readSharedArray(31);
        bufferPool[32] = rc.readSharedArray(32);
        bufferPool[33] = rc.readSharedArray(33);
        bufferPool[34] = rc.readSharedArray(34);
        bufferPool[35] = rc.readSharedArray(35);
        bufferPool[36] = rc.readSharedArray(36);
        bufferPool[37] = rc.readSharedArray(37);
        bufferPool[38] = rc.readSharedArray(38);
        bufferPool[39] = rc.readSharedArray(39);
        bufferPool[40] = rc.readSharedArray(40);
        bufferPool[41] = rc.readSharedArray(41);
        bufferPool[42] = rc.readSharedArray(42);
        bufferPool[43] = rc.readSharedArray(43);
        bufferPool[44] = rc.readSharedArray(44);
        bufferPool[45] = rc.readSharedArray(45);
        bufferPool[46] = rc.readSharedArray(46);
        bufferPool[47] = rc.readSharedArray(47);
        bufferPool[48] = rc.readSharedArray(48);
        bufferPool[49] = rc.readSharedArray(49);
        bufferPool[50] = rc.readSharedArray(50);
        bufferPool[51] = rc.readSharedArray(51);
        bufferPool[52] = rc.readSharedArray(52);
        bufferPool[53] = rc.readSharedArray(53);
        bufferPool[54] = rc.readSharedArray(54);
        bufferPool[55] = rc.readSharedArray(55);
        bufferPool[56] = rc.readSharedArray(56);
        bufferPool[57] = rc.readSharedArray(57);
        bufferPool[58] = rc.readSharedArray(58);
        bufferPool[59] = rc.readSharedArray(59);
        bufferPool[60] = rc.readSharedArray(60);
        bufferPool[61] = rc.readSharedArray(61);
        bufferPool[62] = rc.readSharedArray(62);
        bufferPool[63] = rc.readSharedArray(63);
    }

    public static void flushBufferPool() throws GameActionException {
        if (dirtyFlags[0])
            rc.writeSharedArray(0, bufferPool[0]);
        if (dirtyFlags[1])
            rc.writeSharedArray(1, bufferPool[1]);
        if (dirtyFlags[2])
            rc.writeSharedArray(2, bufferPool[2]);
        if (dirtyFlags[3])
            rc.writeSharedArray(3, bufferPool[3]);
        if (dirtyFlags[4])
            rc.writeSharedArray(4, bufferPool[4]);
        if (dirtyFlags[5])
            rc.writeSharedArray(5, bufferPool[5]);
        if (dirtyFlags[6])
            rc.writeSharedArray(6, bufferPool[6]);
        if (dirtyFlags[7])
            rc.writeSharedArray(7, bufferPool[7]);
        if (dirtyFlags[8])
            rc.writeSharedArray(8, bufferPool[8]);
        if (dirtyFlags[9])
            rc.writeSharedArray(9, bufferPool[9]);
        if (dirtyFlags[10])
            rc.writeSharedArray(10, bufferPool[10]);
        if (dirtyFlags[11])
            rc.writeSharedArray(11, bufferPool[11]);
        if (dirtyFlags[12])
            rc.writeSharedArray(12, bufferPool[12]);
        if (dirtyFlags[13])
            rc.writeSharedArray(13, bufferPool[13]);
        if (dirtyFlags[14])
            rc.writeSharedArray(14, bufferPool[14]);
        if (dirtyFlags[15])
            rc.writeSharedArray(15, bufferPool[15]);
        if (dirtyFlags[16])
            rc.writeSharedArray(16, bufferPool[16]);
        if (dirtyFlags[17])
            rc.writeSharedArray(17, bufferPool[17]);
        if (dirtyFlags[18])
            rc.writeSharedArray(18, bufferPool[18]);
        if (dirtyFlags[19])
            rc.writeSharedArray(19, bufferPool[19]);
        if (dirtyFlags[20])
            rc.writeSharedArray(20, bufferPool[20]);
        if (dirtyFlags[21])
            rc.writeSharedArray(21, bufferPool[21]);
        if (dirtyFlags[22])
            rc.writeSharedArray(22, bufferPool[22]);
        if (dirtyFlags[23])
            rc.writeSharedArray(23, bufferPool[23]);
        if (dirtyFlags[24])
            rc.writeSharedArray(24, bufferPool[24]);
        if (dirtyFlags[25])
            rc.writeSharedArray(25, bufferPool[25]);
        if (dirtyFlags[26])
            rc.writeSharedArray(26, bufferPool[26]);
        if (dirtyFlags[27])
            rc.writeSharedArray(27, bufferPool[27]);
        if (dirtyFlags[28])
            rc.writeSharedArray(28, bufferPool[28]);
        if (dirtyFlags[29])
            rc.writeSharedArray(29, bufferPool[29]);
        if (dirtyFlags[30])
            rc.writeSharedArray(30, bufferPool[30]);
        if (dirtyFlags[31])
            rc.writeSharedArray(31, bufferPool[31]);
        if (dirtyFlags[32])
            rc.writeSharedArray(32, bufferPool[32]);
        if (dirtyFlags[33])
            rc.writeSharedArray(33, bufferPool[33]);
        if (dirtyFlags[34])
            rc.writeSharedArray(34, bufferPool[34]);
        if (dirtyFlags[35])
            rc.writeSharedArray(35, bufferPool[35]);
        if (dirtyFlags[36])
            rc.writeSharedArray(36, bufferPool[36]);
        if (dirtyFlags[37])
            rc.writeSharedArray(37, bufferPool[37]);
        if (dirtyFlags[38])
            rc.writeSharedArray(38, bufferPool[38]);
        if (dirtyFlags[39])
            rc.writeSharedArray(39, bufferPool[39]);
        if (dirtyFlags[40])
            rc.writeSharedArray(40, bufferPool[40]);
        if (dirtyFlags[41])
            rc.writeSharedArray(41, bufferPool[41]);
        if (dirtyFlags[42])
            rc.writeSharedArray(42, bufferPool[42]);
        if (dirtyFlags[43])
            rc.writeSharedArray(43, bufferPool[43]);
        if (dirtyFlags[44])
            rc.writeSharedArray(44, bufferPool[44]);
        if (dirtyFlags[45])
            rc.writeSharedArray(45, bufferPool[45]);
        if (dirtyFlags[46])
            rc.writeSharedArray(46, bufferPool[46]);
        if (dirtyFlags[47])
            rc.writeSharedArray(47, bufferPool[47]);
        if (dirtyFlags[48])
            rc.writeSharedArray(48, bufferPool[48]);
        if (dirtyFlags[49])
            rc.writeSharedArray(49, bufferPool[49]);
        if (dirtyFlags[50])
            rc.writeSharedArray(50, bufferPool[50]);
        if (dirtyFlags[51])
            rc.writeSharedArray(51, bufferPool[51]);
        if (dirtyFlags[52])
            rc.writeSharedArray(52, bufferPool[52]);
        if (dirtyFlags[53])
            rc.writeSharedArray(53, bufferPool[53]);
        if (dirtyFlags[54])
            rc.writeSharedArray(54, bufferPool[54]);
        if (dirtyFlags[55])
            rc.writeSharedArray(55, bufferPool[55]);
        if (dirtyFlags[56])
            rc.writeSharedArray(56, bufferPool[56]);
        if (dirtyFlags[57])
            rc.writeSharedArray(57, bufferPool[57]);
        if (dirtyFlags[58])
            rc.writeSharedArray(58, bufferPool[58]);
        if (dirtyFlags[59])
            rc.writeSharedArray(59, bufferPool[59]);
        if (dirtyFlags[60])
            rc.writeSharedArray(60, bufferPool[60]);
        if (dirtyFlags[61])
            rc.writeSharedArray(61, bufferPool[61]);
        if (dirtyFlags[62])
            rc.writeSharedArray(62, bufferPool[62]);
        if (dirtyFlags[63])
            rc.writeSharedArray(63, bufferPool[63]);
    }


    public static void readAllSectorMessages() {
        if (read(25) == 0) return;
        sectorMessages.add(read(25));

        if (read(26) == 0) return;
        sectorMessages.add(read(26));

        if (read(27) == 0) return;
        sectorMessages.add(read(27));

        if (read(28) == 0) return;
        sectorMessages.add(read(28));

        if (read(29) == 0) return;
        sectorMessages.add(read(29));

        if (read(30) == 0) return;
        sectorMessages.add(read(30));

        if (read(31) == 0) return;
        sectorMessages.add(read(31));

        if (read(32) == 0) return;
        sectorMessages.add(read(32));

        if (read(33) == 0) return;
        sectorMessages.add(read(33));

        if (read(34) == 0) return;
        sectorMessages.add(read(34));

        if (read(35) == 0) return;
        sectorMessages.add(read(35));

        if (read(36) == 0) return;
        sectorMessages.add(read(36));

        if (read(37) == 0) return;
        sectorMessages.add(read(37));

        if (read(38) == 0) return;
        sectorMessages.add(read(38));

        if (read(39) == 0) return;
        sectorMessages.add(read(39));

        if (read(40) == 0) return;
        sectorMessages.add(read(40));

        if (read(41) == 0) return;
        sectorMessages.add(read(41));

        if (read(42) == 0) return;
        sectorMessages.add(read(42));

        if (read(43) == 0) return;
        sectorMessages.add(read(43));

        if (read(44) == 0) return;
        sectorMessages.add(read(44));        
    }

    public static void clearSectorSlots() throws GameActionException {
        writeToBufferPool(25, 0);
        writeToBufferPool(26, 0);
        writeToBufferPool(27, 0);
        writeToBufferPool(28, 0);
        writeToBufferPool(29, 0);
        writeToBufferPool(30, 0);
        writeToBufferPool(31, 0);
        writeToBufferPool(32, 0);
        writeToBufferPool(33, 0);
        writeToBufferPool(34, 0);
        writeToBufferPool(35, 0);
        writeToBufferPool(36, 0);
        writeToBufferPool(37, 0);
        writeToBufferPool(38, 0);
        writeToBufferPool(39, 0);
        writeToBufferPool(40, 0);
        writeToBufferPool(41, 0);
        writeToBufferPool(42, 0);
        writeToBufferPool(43, 0);
        writeToBufferPool(44, 0);
    }

    public static void writeNextSectorPacket(int packet) throws GameActionException {
        if (read(25) == 0) { writeToBufferPool(25, packet); return; }
        if (read(25) == packet) return;
        
        if (read(26) == 0) { writeToBufferPool(26, packet); return; }
        if (read(26) == packet) return;

        if (read(27) == 0) { writeToBufferPool(27, packet); return; }
        if (read(27) == packet) return;

        if (read(28) == 0) { writeToBufferPool(28, packet); return; }
        if (read(28) == packet) return;

        if (read(29) == 0) { writeToBufferPool(29, packet); return; }
        if (read(29) == packet) return;

        if (read(30) == 0) { writeToBufferPool(30, packet); return; }
        if (read(30) == packet) return;

        if (read(31) == 0) { writeToBufferPool(31, packet); return; }
        if (read(31) == packet) return;

        if (read(32) == 0) { writeToBufferPool(32, packet); return; }
        if (read(32) == packet) return;

        if (read(33) == 0) { writeToBufferPool(33, packet); return; }
        if (read(33) == packet) return;

        if (read(34) == 0) { writeToBufferPool(34, packet); return; }
        if (read(34) == packet) return;

        if (read(35) == 0) { writeToBufferPool(35, packet); return; }
        if (read(35) == packet) return;

        if (read(36) == 0) { writeToBufferPool(36, packet); return; }
        if (read(36) == packet) return;

        if (read(37) == 0) { writeToBufferPool(37, packet); return; }
        if (read(37) == packet) return;

        if (read(38) == 0) { writeToBufferPool(38, packet); return; }
        if (read(38) == packet) return;

        if (read(39) == 0) { writeToBufferPool(39, packet); return; }
        if (read(39) == packet) return;

        if (read(40) == 0) { writeToBufferPool(40, packet); return; }
        if (read(40) == packet) return;

        if (read(41) == 0) { writeToBufferPool(41, packet); return; }
        if (read(41) == packet) return;

        if (read(42) == 0) { writeToBufferPool(42, packet); return; }
        if (read(42) == packet) return;

        if (read(43) == 0) { writeToBufferPool(43, packet); return; }
        if (read(43) == packet) return;

        if (read(44) == 0) { writeToBufferPool(44, packet); return; }
        if (read(44) == packet) return;
    }
}

class SectorInfo {
    MapLocation loc;
    int round;
    int type;
    int flagID;
    public SectorInfo(MapLocation loc, int round) {
        this.loc = loc;
        this.round = round;
    }
}