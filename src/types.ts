export type VaultEntry = {
  id: string;
  title: string;
  username: string;
  password: string;
  favorite: boolean;
  updatedAt: number;
};

export const emptyEntry = (): VaultEntry => ({
  id: crypto.randomUUID(),
  title: '',
  username: '',
  password: '',
  favorite: false,
  updatedAt: Date.now(),
});
