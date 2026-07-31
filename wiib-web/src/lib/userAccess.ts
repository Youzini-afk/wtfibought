import type { User } from '../types';

export const USER_ROLE = {
  user: 1,
  admin: 10,
  owner: 100,
} as const;

export const USER_STATUS = {
  active: 1,
  disabled: 2,
} as const;

type UserAccessView = Pick<User, 'id'> & Partial<Pick<User, 'role'>>;

export function isAdminUser(user: UserAccessView | null | undefined): boolean {
  if (!user) return false;
  if (user.role == null) return user.id === 1;
  return user.role >= USER_ROLE.admin;
}

export function isOwnerUser(user: UserAccessView | null | undefined): boolean {
  if (!user || user.id !== 1) return false;
  return user.role == null || user.role >= USER_ROLE.owner;
}
