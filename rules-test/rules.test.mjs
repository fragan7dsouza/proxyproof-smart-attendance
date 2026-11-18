// Attacks every anti-proxy rule in ../firestore.rules against the local Firestore emulator.
// Run from this folder:  npx firebase emulators:exec --only firestore --project demo-proxyproof "npm test"
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails, assertSucceeds, initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc, writeBatch,
} from 'firebase/firestore';

const TOKEN = 'AbCdEfGh12345678';
const LAT = 19.0760, LNG = 72.8777;
const M_PER_DEG_LNG = 111320 * Math.cos(LAT * Math.PI / 180);
const metresNorth = (m) => LAT + m / 111320;
const OLD_TOKEN = 'ZyXwVuTs87654321';
let env;

const db = (uid) => env.authenticatedContext(uid).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-proxyproof',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(() => env.cleanup());

// A known world: one faculty, one course, two enrolled students on their own phones, a live class.
async function seed({ tokenAgeSec = 2, active = true } = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    await setDoc(doc(f, 'config/faculty'), { code: 'STAFF-2026' });
    await setDoc(doc(f, 'users/prof'), { name: 'Prof Rao', role: 'faculty', deviceId: null });
    await setDoc(doc(f, 'users/prof2'), { name: 'Prof Other', role: 'faculty', deviceId: null });
    await setDoc(doc(f, 'courses/c9'), { name: 'Other', code: 'X9', joinCode: 'ZZZZZZ', facultyId: 'prof2' });
    for (const [uid, dev] of [['asha', 'phoneA'], ['ravi', 'phoneR']]) {
      await setDoc(doc(f, `users/${uid}`), { name: uid, role: 'student', deviceId: dev, rollNo: uid.toUpperCase() });
      await setDoc(doc(f, `devices/${dev}`), { uid });
      await setDoc(doc(f, `enrollments/c1_${uid}`), { courseId: 'c1', studentId: uid });
    }
    await setDoc(doc(f, 'users/outsider'), { name: 'outsider', role: 'student', deviceId: 'phoneO' });
    await setDoc(doc(f, 'devices/phoneO'), { uid: 'outsider' });
    await setDoc(doc(f, 'courses/c1'), { name: 'MAD', code: 'CS301', joinCode: 'K7P2QX', facultyId: 'prof' });
    await setDoc(doc(f, 'sessions/s1'), { courseId: 'c1', facultyId: 'prof', active, radiusM: 50, lat: LAT, lng: LNG, mPerDegLng: M_PER_DEG_LNG });
    await setDoc(doc(f, 'sessions/s1/secret/token'), {
      token: TOKEN, prevToken: OLD_TOKEN,
      tokenAt: Timestamp.fromMillis(Date.now() - tokenAgeSec * 1000),
    });
  });
}

const mark = (overrides = {}) => ({
  sessionId: 's1', courseId: 'c1', facultyId: 'prof', studentId: 'asha', studentName: 'asha', rollNo: 'ASHA',
  deviceId: 'phoneA', token: TOKEN, distanceM: 12, lat: metresNorth(12), lng: LNG,
  markedAt: serverTimestamp(), ...overrides,
});

beforeEach(() => env.clearFirestore());

describe('attendance: the happy path', () => {
  test('enrolled student, own phone, fresh QR, inside geofence -> accepted', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
  });

  test('previous QR still accepted a few seconds after it changed', async () => {
    await seed({ tokenAgeSec: 4 });
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ token: OLD_TOKEN })));
  });
});

describe('attendance: proxy attempts', () => {
  test('marking twice for the same class is rejected', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
  });

  test('marking for a friend (their id) is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_ravi'), mark({ studentId: 'ravi', deviceId: 'phoneR' })));
  });

  test('using a friend\'s phone id is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ deviceId: 'phoneR' })));
  });

  test('screenshot forwarded later: expired QR is rejected', async () => {
    await seed({ tokenAgeSec: 45 });
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
  });

  test('older QR past its grace period is rejected', async () => {
    await seed({ tokenAgeSec: 12 });
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ token: OLD_TOKEN })));
  });

  test('guessed or made-up token is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ token: 'AAAAAAAAAAAAAAAA' })));
  });

  test('outside the geofence is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ lat: metresNorth(60) })));
  });

  test('modified app claiming distance 0 from the hostel is still rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'),
      mark({ distanceM: 0, lat: metresNorth(800) })));
  });

  test('missing coordinates are rejected', async () => {
    await seed();
    const { lat, lng, ...noCoords } = mark();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), noCoords));
  });

  test('student not enrolled in the course is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('outsider'), 'attendance/s1_outsider'),
      mark({ studentId: 'outsider', deviceId: 'phoneO' })));
  });

  test('class already ended is rejected', async () => {
    await seed({ active: false });
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
  });

  test('back-dated timestamp from the phone is rejected', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ markedAt: Timestamp.now() })));
  });

  test('students cannot read the live token (must scan it in the room)', async () => {
    await seed();
    await assertFails(getDoc(doc(db('asha'), 'sessions/s1/secret/token')));
    await assertSucceeds(getDoc(doc(db('prof'), 'sessions/s1/secret/token')));
  });

  test('records cannot be edited or deleted, even by their owner', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
    await assertFails(updateDoc(doc(db('asha'), 'attendance/s1_asha'), { distanceM: 1 }));
    await assertFails(deleteDoc(doc(db('asha'), 'attendance/s1_asha')));
    await assertFails(deleteDoc(doc(db('prof'), 'attendance/s1_asha')));
  });

  test('students cannot read other students\' records', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
    await assertFails(getDoc(doc(db('ravi'), 'attendance/s1_asha')));
    await assertSucceeds(getDoc(doc(db('prof'), 'attendance/s1_asha')));
  });
});

describe('device binding', () => {
  test('a new student registers with this phone', async () => {
    const neha = db('neha');
    const b = writeBatch(neha);
    b.set(doc(neha, 'devices/phoneN'), { uid: 'neha' });
    b.set(doc(neha, 'users/neha'), { name: 'Neha', role: 'student', deviceId: 'phoneN' });
    await assertSucceeds(b.commit());
  });

  test('a second account cannot register on a phone that is already taken', async () => {
    await seed();
    const fake = db('fake');
    const b = writeBatch(fake);
    b.set(doc(fake, 'devices/phoneA'), { uid: 'fake' });
    b.set(doc(fake, 'users/fake'), { name: 'Fake', role: 'student', deviceId: 'phoneA' });
    await assertFails(b.commit());
  });

  test('a student cannot move their own binding or promote themselves', async () => {
    await seed();
    await assertFails(updateDoc(doc(db('asha'), 'users/asha'), { deviceId: 'phoneX' }));
    await assertFails(updateDoc(doc(db('asha'), 'users/asha'), { role: 'faculty' }));
  });

  test('faculty reset, then the student binds their new phone', async () => {
    await seed();
    const prof = db('prof');
    const reset = writeBatch(prof);
    reset.update(doc(prof, 'users/asha'), { deviceId: null, resetCourseId: 'c1' });
    reset.delete(doc(prof, 'devices/phoneA'));
    await assertSucceeds(reset.commit());

    const asha = db('asha');
    const bind = writeBatch(asha);
    bind.set(doc(asha, 'devices/phoneA2'), { uid: 'asha' });
    bind.update(doc(asha, 'users/asha'), { deviceId: 'phoneA2' });
    await assertSucceeds(bind.commit());
  });

  test('faculty who do not teach the student cannot reset their phone', async () => {
    await seed();
    const other = db('prof2');
    const b = writeBatch(other);
    b.update(doc(other, 'users/asha'), { deviceId: null, resetCourseId: 'c9' });
    b.delete(doc(other, 'devices/phoneA'));
    await assertFails(b.commit());
    await assertFails(deleteDoc(doc(db('prof'), 'devices/phoneA'))); // delete alone, without a reset
  });

  test('a student cannot clear another student\'s binding', async () => {
    await seed();
    await assertFails(updateDoc(doc(db('asha'), 'users/ravi'), { deviceId: null }));
  });
});

describe('faculty sign-up and privacy', () => {
  const facultyProfile = (code) => ({ name: 'New Prof', role: 'faculty', deviceId: null, facultyCode: code });

  test('faculty sign-up needs the admin\'s access code', async () => {
    await seed();
    await assertFails(setDoc(doc(db('sneaky'), 'users/sneaky'), { name: 'Sneaky', role: 'faculty', deviceId: null }));
    await assertFails(setDoc(doc(db('sneaky'), 'users/sneaky'), facultyProfile('GUESS')));
    await assertSucceeds(setDoc(doc(db('newprof'), 'users/newprof'), facultyProfile('STAFF-2026')));
  });

  test('nobody can read the access code', async () => {
    await seed();
    await assertFails(getDoc(doc(db('asha'), 'config/faculty')));
    await assertFails(getDoc(doc(db('prof'), 'config/faculty')));
  });

  test('students cannot read other students\' profiles', async () => {
    await seed();
    await assertSucceeds(getDoc(doc(db('asha'), 'users/asha')));
    await assertFails(getDoc(doc(db('asha'), 'users/ravi')));
  });

  test('faculty cannot read attendance of classes they did not run', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark()));
    await assertFails(getDoc(doc(db('prof2'), 'attendance/s1_asha')));
  });

  test('a record cannot claim a different faculty', async () => {
    await seed();
    await assertFails(setDoc(doc(db('asha'), 'attendance/s1_asha'), mark({ facultyId: 'prof2' })));
  });
});

describe('courses and classes', () => {
  test('only faculty create courses and start classes, for their own course', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('prof'), 'courses/c2'),
      { name: 'DBMS', code: 'CS302', joinCode: 'ABCDEF', facultyId: 'prof' }));
    await assertFails(setDoc(doc(db('asha'), 'courses/c3'),
      { name: 'Fake', code: 'X', joinCode: 'ABCDEF', facultyId: 'asha' }));
    await assertSucceeds(setDoc(doc(db('prof'), 'sessions/s2'),
      { courseId: 'c1', facultyId: 'prof', active: true, radiusM: 50, lat: LAT, lng: LNG, mPerDegLng: M_PER_DEG_LNG }));
    await assertFails(setDoc(doc(db('asha'), 'sessions/s3'),
      { courseId: 'c1', facultyId: 'asha', active: true, radiusM: 50 }));
  });

  test('student joins with the right enrolment id only', async () => {
    await seed();
    await assertSucceeds(setDoc(doc(db('outsider'), 'enrollments/c1_outsider'),
      { courseId: 'c1', studentId: 'outsider' }));
    await assertFails(setDoc(doc(db('outsider'), 'enrollments/c1_ravi'),
      { courseId: 'c1', studentId: 'ravi' }));
  });

  test('faculty can end their class; students cannot', async () => {
    await seed();
    await assertFails(updateDoc(doc(db('asha'), 'sessions/s1'), { active: false }));
    await assertSucceeds(updateDoc(doc(db('prof'), 'sessions/s1'), { active: false, endedAt: serverTimestamp() }));
  });
});
