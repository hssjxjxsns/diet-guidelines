import { Platform } from 'react-native';

const DEFAULT_BASE_URL =
  Platform.OS === 'android'
    ? 'http://10.0.2.2:9000/api'
    : 'http://localhost:9000/api';

export const API_BASE_URL = (
  process.env.EXPO_PUBLIC_API_BASE_URL ?? DEFAULT_BASE_URL
).replace(/\/$/, '');
